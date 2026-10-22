package com.kidsgarden.app

import android.content.Context
import com.kidsgarden.app.calls.TelephonyCallStateMonitor
import com.kidsgarden.app.data.DataStoreProvider
import com.kidsgarden.app.data.SettingsDocument
import com.kidsgarden.app.data.BrightnessRestore
import com.kidsgarden.app.data.SettingsRepository
import com.kidsgarden.app.domain.ActivationStore
import com.kidsgarden.app.effects.AndroidEffectOutputs
import com.kidsgarden.app.effects.BrightnessDimmer
import com.kidsgarden.app.effects.BrightnessStore
import com.kidsgarden.app.effects.EffectCapabilityProvider
import com.kidsgarden.app.effects.GreyOverlay
import com.kidsgarden.app.effects.MediaVolumeCap
import com.kidsgarden.app.effects.ScreenEffectsController
import com.kidsgarden.app.effects.defaultEffectTiming
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.SessionManager
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.domain.VpnLifecycle
import com.kidsgarden.app.permissions.AndroidSetupStatusProvider
import com.kidsgarden.app.permissions.PermissionStatusProvider
import com.kidsgarden.app.platform.time.AndroidTimeProvider
import com.kidsgarden.app.receivers.DeviceEventCoordinator
import com.kidsgarden.app.security.ParentPinManager
import com.kidsgarden.app.security.PinHasher
import com.kidsgarden.app.vpn.AndroidCompatibilityProbe
import com.kidsgarden.app.vpn.AndroidVpnServiceLauncher
import com.kidsgarden.app.vpn.ServiceBoundaryScheduler
import com.kidsgarden.app.vpn.Tun2SocksForwardingEngine
import com.kidsgarden.app.vpn.VpnDullingController
import com.kidsgarden.app.vpn.VpnUnavailableReason
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

    /**
     * Active/Deactivated as last committed in this process. Set by Activate/Deactivate
     * before the VPN starts or stops, so a start never races the settings collector.
     */
    @Volatile
    private var activeNow: Boolean? = null

    val pinManager = ParentPinManager(settingsRepository, PinHasher(), time)

    /** Applies network policy. Started only from the visible parent setup flow (T046). */
    val vpnController = VpnDullingController(
        engine = Tun2SocksForwardingEngine(),
        launcher = AndroidVpnServiceLauncher(appContext),
        profiles = { _settings.value?.vpnDullingConfiguration ?: SettingsDocument.DEFAULT_PROFILES },
        selectedIntensity = { _settings.value?.dullIntensity ?: DullIntensity.MEDIUM },
        // Deactivated is read from the saved setting itself, so nothing restarts the VPN then.
        startAllowed = { activeNow ?: (_settings.value?.active != false) },
        probe = AndroidCompatibilityProbe(appContext),
    )

    /** Live access for the three screen and sound effects (FR-036). */
    val effectCapability = EffectCapabilityProvider(appContext)

    /** Grey screen, media volume and brightness (T115). Runs on the main thread. */
    val screenEffects = ScreenEffectsController(
        outputs = AndroidEffectOutputs(GreyOverlay(appContext), MediaVolumeCap(appContext), BrightnessDimmer(appContext)),
        brightness = object : BrightnessStore {
            override fun current(): BrightnessRestore? = _settings.value?.brightnessRestore
            override suspend fun save(value: BrightnessRestore?): Boolean =
                settingsRepository.saveBrightnessRestore(value).isSuccess
        },
        timing = {
            _settings.value?.screenEffects?.let { it.buildUpSeconds * 1_000L to it.breakSeconds * 1_000L }
                ?: defaultEffectTiming()
        },
        elapsed = time::elapsedRealtimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
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
        activationStore = ActivationStore { active -> settingsRepository.updateActive(active).isSuccess },
        vpnLifecycle = object : VpnLifecycle {
            // Activate: restart network dulling if the parent has it on; consent is rechecked.
            override fun startVpn() {
                activeNow = true
                val document = _settings.value ?: return
                if (document.vpnDullingEnabled && document.setup.onboardingComplete) vpnController.requestStart()
            }

            override fun stopVpn() {
                activeNow = false
                vpnController.stop(VpnUnavailableReason.DEACTIVATED)
            }
        },
        effectSink = screenEffects,
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
                    if (document.active && document.vpnDullingEnabled && document.setup.onboardingComplete) {
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
                    // Unreadable settings mean Active (FR-031): start at DULL.
                    if (first) sessionManager.dispatch(DomainEvent.ProcessStarted(savedActive = true))
                    first = false
                }
                .collect { document ->
                    storageAvailable.value = true
                    _settings.value = document
                    sessionManager.dispatch(DomainEvent.SettingsChanged(document.toDomainSettings()))
                    if (first) {
                        // Brightness left lowered by process death goes back first (FR-034c).
                        screenEffects.restoreLeftover()
                        // A new process starts at DULL with no grant (FR-027), or at
                        // DEACTIVATED when valid saved settings say so (FR-031). Repaired
                        // settings are defaults, which are Active.
                        val repaired = DataStoreProvider.repairedSinceStart
                        activeNow = document.active || repaired
                        if (repaired) sessionManager.dispatch(DomainEvent.SettingsInvalid)
                        sessionManager.dispatch(DomainEvent.ProcessStarted(savedActive = document.active && !repaired))
                    }
                    first = false
                }
        }
        callMonitor.start()
        appScope.launch {
            callMonitor.observations.collect { sessionManager.dispatch(DomainEvent.CallAggregateChanged(it)) }
        }
        appScope.launch {
            effectCapability.access.collect { sessionManager.dispatch(DomainEvent.EffectCapabilityChanged(it)) }
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
