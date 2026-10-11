package com.kidsgarden.app

import android.content.Context
import com.kidsgarden.app.data.DataStoreProvider
import com.kidsgarden.app.data.SettingsDocument
import com.kidsgarden.app.data.SettingsRepository
import com.kidsgarden.app.domain.BoundaryScheduler
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    val sessionManager = SessionManager(
        time = time,
        policySink = vpnController,
        boundaryScheduler = NoBoundariesYet,
        scope = appScope,
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

    init {
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
        appScope.launch {
            vpnController.capability
                .map { it.capability }
                .distinctUntilChanged()
                .collect { sessionManager.dispatch(DomainEvent.VpnCapabilityChanged(it)) }
        }
    }

    /** Placeholder until ServiceBoundaryScheduler exists (T060); no grants exist before then. */
    private object NoBoundariesYet : BoundaryScheduler {
        override fun schedule(atElapsedMillis: Long, generation: Long) = Unit
        override fun cancel() = Unit
    }
}
