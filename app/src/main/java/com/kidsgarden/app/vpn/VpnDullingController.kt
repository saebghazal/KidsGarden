package com.kidsgarden.app.vpn

import com.kidsgarden.app.data.ThrottleSettings
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.NetworkPolicy
import com.kidsgarden.app.domain.NetworkPolicySink
import com.kidsgarden.app.domain.VpnCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Coarse reasons the parent can be shown. No addresses, traffic, or exception text. */
enum class VpnUnavailableReason {
    NOT_STARTED,
    CONSENT_MISSING,
    FOREGROUND_NOT_ALLOWED,
    START_TIMEOUT,
    ESTABLISH_FAILED,
    ENGINE_FAILED,
    REVOKED,
    STOPPED,
}

/** Live capability. [reason] is set only while not RUNNING. */
data class VpnCapabilitySnapshot(
    val capability: VpnCapability,
    val reason: VpnUnavailableReason?,
)

/** Starts the VPN service and checks live consent. Implemented with Android in the app. */
interface VpnServiceLauncher {
    /** `VpnService.prepare` returns null. Never answered from the saved preference. */
    fun consentGranted(): Boolean

    /** Starts the foreground VPN service from a visible parent screen. */
    fun startService()
}

/** The running service, so the controller can shut it down (closing the TUN). */
fun interface VpnServiceHandle {
    fun shutdown(reason: VpnUnavailableReason)
}

/**
 * Applies domain network policy to the forwarding engine (T043,
 * contracts/vpn-dulling-controller.md).
 *
 * - Capability is RUNNING only after the engine reports real forwarding.
 * - Stale generations are ignored.
 * - While the engine runs, an Unavailable policy keeps the selected Dull profile; the
 *   engine is never told to forward unshaped unless the domain asks for Normal.
 * - Every stop closes the TUN, so the device falls back to ordinary routing rather than
 *   losing network access.
 */
class VpnDullingController(
    private val engine: ForwardingEngine,
    private val launcher: VpnServiceLauncher,
    private val profiles: () -> Map<DullIntensity, ThrottleSettings>,
    private val selectedIntensity: () -> DullIntensity,
    private val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MS,
) : NetworkPolicySink {

    private val mutex = Mutex()
    private val _capability = MutableStateFlow(VpnCapabilitySnapshot(VpnCapability.UNAVAILABLE, VpnUnavailableReason.NOT_STARTED))
    val capability: StateFlow<VpnCapabilitySnapshot> = _capability.asStateFlow()

    private var latestGeneration = Long.MIN_VALUE
    private var latestPolicy: NetworkPolicy? = null

    @Volatile
    private var engineRunning = false

    @Volatile
    private var service: VpnServiceHandle? = null

    fun consentGranted(): Boolean = launcher.consentGranted()

    override suspend fun apply(policy: NetworkPolicy, generation: Long) {
        mutex.withLock {
            if (generation < latestGeneration) return
            latestGeneration = generation
            latestPolicy = policy
            if (engineRunning) engine.apply(currentProfile(), generation)
        }
    }

    /**
     * Starts the VPN from a visible parent screen and waits for the outcome. Consent is
     * rechecked here because it may have changed since the screen was drawn.
     */
    suspend fun start(): VpnCapabilitySnapshot {
        val current = _capability.value
        if (current.capability == VpnCapability.RUNNING) return current
        if (!launcher.consentGranted()) return report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.CONSENT_MISSING)
        report(VpnCapability.STARTING, null)
        try {
            launcher.startService()
        } catch (_: IllegalStateException) {
            return report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.FOREGROUND_NOT_ALLOWED)
        } catch (_: SecurityException) {
            return report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.FOREGROUND_NOT_ALLOWED)
        }
        val outcome = withTimeoutOrNull(startTimeoutMillis) {
            _capability.first { it.capability != VpnCapability.STARTING }
        }
        if (outcome == null) {
            stop(VpnUnavailableReason.START_TIMEOUT)
            return report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.START_TIMEOUT)
        }
        return outcome
    }

    /** Idempotent. Shuts the service down if it runs; otherwise just reports [reason]. */
    fun stop(reason: VpnUnavailableReason) {
        val handle = service
        if (handle != null) handle.shutdown(reason) else onTunnelClosed(reason)
    }

    // --- Called by KidsGardenVpnService ---

    fun attachService(handle: VpnServiceHandle) {
        service = handle
    }

    fun detachService(handle: VpnServiceHandle) {
        if (service === handle) service = null
    }

    /** The TUN exists; start forwarding. Returns false if the service must shut down. */
    suspend fun onTunnelEstablished(tunFd: Int, protector: SocketProtector, dns: UpstreamDnsSource): Boolean {
        val initial = mutex.withLock { currentProfile() }
        val result = engine.start(EngineConfiguration(), tunFd, protector, dns, initial) { onEngineFailed() }
        return when (result) {
            EngineStartResult.Running -> {
                mutex.withLock {
                    engineRunning = true
                    engine.apply(currentProfile(), latestGeneration)
                }
                report(VpnCapability.RUNNING, null)
                true
            }
            is EngineStartResult.Failed -> {
                engine.stop()
                report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.ENGINE_FAILED)
                false
            }
        }
    }

    /** The TUN is closing or never opened. Stops forwarding and reports [reason]. */
    fun onTunnelClosed(reason: VpnUnavailableReason) {
        engineRunning = false
        engine.stop()
        report(VpnCapability.UNAVAILABLE, reason)
    }

    private fun onEngineFailed() {
        engineRunning = false
        report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.ENGINE_FAILED)
        // Close the TUN so traffic falls back to ordinary routing.
        service?.shutdown(VpnUnavailableReason.ENGINE_FAILED)
    }

    /** The profile to enforce now. Callers hold [mutex]. */
    internal fun currentProfile(): ThrottleProfile {
        val table = profiles()
        val policy = latestPolicy ?: NetworkPolicy.Dull(selectedIntensity())
        return when (val decision = VpnPolicyMapper.map(policy, table)) {
            is ShapingDecision.Shape -> decision.profile
            is ShapingDecision.Unavailable -> ThrottleProfile.from(table.getValue(selectedIntensity()))
        }
    }

    private fun report(capability: VpnCapability, reason: VpnUnavailableReason?): VpnCapabilitySnapshot {
        val snapshot = VpnCapabilitySnapshot(capability, if (capability == VpnCapability.RUNNING) null else reason)
        _capability.value = snapshot
        return snapshot
    }

    companion object {
        const val DEFAULT_START_TIMEOUT_MS = 20_000L
    }
}
