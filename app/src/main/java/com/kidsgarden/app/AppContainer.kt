package com.kidsgarden.app

import android.content.Context
import com.kidsgarden.app.calls.TelephonyCallStateMonitor
import com.kidsgarden.app.data.DataStoreProvider
import com.kidsgarden.app.data.SettingsDocument
import com.kidsgarden.app.data.SettingsRepository
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.SessionManager
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.permissions.AndroidSetupStatusProvider
import com.kidsgarden.app.permissions.PermissionStatusProvider
import com.kidsgarden.app.platform.time.AndroidTimeProvider
import com.kidsgarden.app.receivers.DeviceEventCoordinator
import com.kidsgarden.app.security.ParentPinManager
import com.kidsgarden.app.security.PinHasher
import com.kidsgarden.app.vpn.AndroidVpnServiceLauncher
import com.kidsgarden.app.vpn.ServiceBoundaryScheduler
import com.kidsgarden.app.vpn.Tun2SocksForwardingEngine
import com.kidsgarden.app.vpn.VpnDullingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Manual dependency container: exactly one instance of each component per process,
 * shared by UI and the VPN service. No DI framework (plan Technical Context).
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val time: TimeProvider = AndroidTimeProvider()

    val settingsRepository = SettingsRepository(DataStoreProvider.create(appContext, ioScope))

    private val _settings = MutableStateFlow<SettingsDocument?>(null)

    /** Latest validated settings; null until the first load. */
    val settings: StateFlow<SettingsDocument?> = _settings.asStateFlow()

    private val storageAvailable = MutableStateFlow(true)

    val pinManager = ParentPinManager(settingsRepository, PinHasher(), time)

    /** Applies network policy. Started only from the visible parent setup flow (T046). */
    val vpnController = VpnDullingController(
        engine = Tun2SocksForwardingEngine(),
        launcher = AndroidVpnServiceLauncher(appContext),
        profiles = { _settings.value?.vpnDullingConfiguration ?: SettingsDocument.DEFAULT_PROFILES },
        selectedIntensity = { _settings.value?.dullIntensity ?: DullIntensity.MEDIUM },
    )

    /** Normal phone calls only (T061). Started at process start; restarts when permission changes. */
    val callMonitor = TelephonyCallStateMonitor(appContext, time)

    val sessionManager: SessionManager = SessionManager(
        time = time,
        policySink = vpnController,
        boundaryScheduler = ServiceBoundaryScheduler { generation ->
            sessionManager.dispatch(DomainEvent.DeadlineObserved(generation))
        },
        scope = appScope,
        callSource = callMonitor,
    )

    /** Screen-off, unlock, and sampled lock state, reported as domain events (T056). */
    val deviceEvents = DeviceEventCoordinator(appContext, sessionManager)

    val setupStatus = AndroidSetupStatusProvider(
        permissions = PermissionStatusProvider(appContext),
        vpnCapability = vpnController.capability,
        settings = settings,
        storageAvailable = storageAvailable,
        scope = appScope,
    )

    /**
     * After boot or an app update, restores VPN dulling if the parent left it on and Android
     * still has consent (T082). Restores protection only: authorization stays DULL with no
     * grant, whatever was stored. Bounded so it fits a broadcast's time budget.
     */
    fun recoverProtection(onDone: () -> Unit) {
        appScope.launch {
            try {
                withTimeoutOrNull(RECOVERY_BUDGET_MS) {
                    val document = settings.filterNotNull().first()
                    if (document.vpnDullingEnabled && document.setup.onboardingComplete) {
                        vpnController.requestStart()
                    }
                }
            } finally {
                onDone()
            }
        }
    }

    init {
        vpnController.onCapabilityLost = { reason -> deviceEvents.vpnLost(reason) }
        deviceEvents.start()
        appScope.launch {
            var first = true
            settingsRepository.observeSettings()
                .catch { error ->
                    if (error !is IOException) throw error
                    storageAvailable.value = false
                }
                .collect { document ->
                    storageAvailable.value = true
                    _settings.value = document
                    if (first && DataStoreProvider.repairedSinceStart) {
                        sessionManager.dispatch(DomainEvent.SettingsInvalid)
                    }
                    first = false
                    sessionManager.dispatch(DomainEvent.SettingsChanged(document.toDomainSettings()))
                }
        }
        callMonitor.start()
        appScope.launch {
            callMonitor.observations.collect { sessionManager.dispatch(DomainEvent.CallAggregateChanged(it)) }
        }
        appScope.launch {
            callMonitor.capability.collect { sessionManager.dispatch(DomainEvent.CallCapabilityChanged(it)) }
        }
        appScope.launch {
            vpnController.capability
                .map { it.capability }
                .distinctUntilChanged()
                .collect { sessionManager.dispatch(DomainEvent.VpnCapabilityChanged(it)) }
        }
    }

    private companion object {
        const val RECOVERY_BUDGET_MS = 8_000L
    }
}
