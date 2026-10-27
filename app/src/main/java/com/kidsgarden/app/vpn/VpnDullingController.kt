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

    /** The parent deactivated Kids Garden: intentionally off, not an error (FR-031). */
    DEACTIVATED,
}

/**
 * Live capability. [reason] is set only while not RUNNING; [exclusions] only while RUNNING.
 * Neither carries package names, addresses, or traffic.
 */
data class VpnCapabilitySnapshot(
    val capability: VpnCapability,
    val reason: VpnUnavailableReason?,
    val exclusions: ExclusionStatus? = null,
)

/** Result of the local compatibility check (FR-032). Runtime only; never stored. */
sealed interface CompatibilityResult {
    data object Pass : CompatibilityResult
    data class Failed(val reason: CompatibilityFailure) : CompatibilityResult
}

enum class CompatibilityFailure {
    NO_CONSENT,
    POLICY_BLOCKED,
    OTHER_VPN,
    FGS_NOT_ALLOWED,
    NO_TRAFFIC,
}

/**
 * Local platform facts for the compatibility check. Reads only on-device state: no network
 * request, no remote destination.
 */
interface CompatibilityProbe {
    /** A device or work policy forbids configuring VPNs. */
    fun vpnBlockedByPolicy(): Boolean

    /** Another app's VPN is active (checked only while ours is not running). */
    fun otherVpnActive(): Boolean
}

/** Starts the VPN service and checks live consent. Implemented with Android in the app. */
interface VpnServiceLauncher {
    /** `VpnService.prepare` returns null. Never answered from the saved preference. */
    fun consentGranted(): Boolean

    /** Starts the foreground VPN service from a visible parent screen. */
    fun startService()

    /** Asks the running service to rebuild its tunnel with the current exclusions (FR-038). */
    fun reconfigure() = Unit
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
    /** False while the parent has Kids Garden Deactivated; the VPN never starts then. */
    private val startAllowed: () -> Boolean = { true },
    private val probe: CompatibilityProbe? = null,
) : NetworkPolicySink {

    private val mutex = Mutex()
    private val _capability = MutableStateFlow(VpnCapabilitySnapshot(VpnCapability.UNAVAILABLE, VpnUnavailableReason.NOT_STARTED))
    val capability: StateFlow<VpnCapabilitySnapshot> = _capability.asStateFlow()

    private var latestGeneration = Long.MIN_VALUE
    private var latestPolicy: NetworkPolicy? = null
    private var latestValidUntil: Long? = null

    @Volatile
    private var engineRunning = false

    @Volatile
    private var service: VpnServiceHandle? = null

    /**
     * Called whenever a running VPN stops for any reason (revoke, another VPN, user stop,
     * engine failure). Lets the session end grants directly, so a quick stop-and-restart
     * can never be missed by a conflated capability flow.
     */
    @Volatile
    var onCapabilityLost: ((VpnUnavailableReason) -> Unit)? = null

    @Volatile
    private var exclusionStatus: ExclusionStatus? = null

    fun consentGranted(): Boolean = launcher.consentGranted()

    /** The service checks this on every start command, so nothing restarts it while Deactivated. */
    fun isStartAllowed(): Boolean = startAllowed()

    override suspend fun apply(policy: NetworkPolicy, generation: Long, validUntilElapsedMillis: Long?) {
        mutex.withLock {
            if (generation < latestGeneration) return
            latestGeneration = generation
            latestPolicy = policy
            latestValidUntil = validUntilElapsedMillis
            if (engineRunning) engine.apply(currentProfile(), generation, currentGuard())
        }
    }

    /**
     * Starts the VPN from a visible parent screen and waits for the outcome. Consent is
     * rechecked here because it may have changed since the screen was drawn.
     */
    suspend fun start(): VpnCapabilitySnapshot {
        val requested = requestStart()
        if (requested.capability != VpnCapability.STARTING) return requested
        val outcome = withTimeoutOrNull(startTimeoutMillis) {
            _capability.first { it.capability != VpnCapability.STARTING }
        }
        if (outcome == null) {
            stop(VpnUnavailableReason.START_TIMEOUT)
            return report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.START_TIMEOUT)
        }
        return outcome
    }

    /**
     * Asks the service to start without waiting for the outcome, for entry points with a
     * short time budget (boot, app update). The service reports the result itself.
     */
    fun requestStart(): VpnCapabilitySnapshot {
        val current = _capability.value
        if (current.capability == VpnCapability.RUNNING || current.capability == VpnCapability.STARTING) return current
        if (!startAllowed()) return report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.DEACTIVATED)
        if (!launcher.consentGranted()) return report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.CONSENT_MISSING)
        report(VpnCapability.STARTING, null)
        return try {
            launcher.startService()
            _capability.value
        } catch (_: IllegalStateException) {
            report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.FOREGROUND_NOT_ALLOWED)
        } catch (_: SecurityException) {
            report(VpnCapability.UNAVAILABLE, VpnUnavailableReason.FOREGROUND_NOT_ALLOWED)
        }
    }

    /**
     * The phone compatibility check (T117, FR-032), run from a visible parent screen. In
     * order: VPN consent, a VPN-blocking policy, another active VPN, then a real start, which
     * proves foreground-service eligibility and, through the engine's local probe, that
     * traffic passes through the TUN. The probe goes to a TEST-NET address that the relay
     * consumes, so nothing leaves the device. A Pass leaves the VPN running. Nothing is
     * stored or logged, and no grant is created.
     */
    suspend fun checkCompatibility(): CompatibilityResult {
        if (!launcher.consentGranted()) return CompatibilityResult.Failed(CompatibilityFailure.NO_CONSENT)
        if (probe?.vpnBlockedByPolicy() == true) return CompatibilityResult.Failed(CompatibilityFailure.POLICY_BLOCKED)
        if (_capability.value.capability == VpnCapability.RUNNING) return CompatibilityResult.Pass
        if (probe?.otherVpnActive() == true) return CompatibilityResult.Failed(CompatibilityFailure.OTHER_VPN)
        val outcome = start()
        if (outcome.capability == VpnCapability.RUNNING) return CompatibilityResult.Pass
        return CompatibilityResult.Failed(
            when (outcome.reason) {
                VpnUnavailableReason.CONSENT_MISSING -> CompatibilityFailure.NO_CONSENT
                VpnUnavailableReason.FOREGROUND_NOT_ALLOWED -> CompatibilityFailure.FGS_NOT_ALLOWED
                VpnUnavailableReason.ESTABLISH_FAILED -> CompatibilityFailure.POLICY_BLOCKED
                VpnUnavailableReason.REVOKED -> CompatibilityFailure.OTHER_VPN
                else -> CompatibilityFailure.NO_TRAFFIC
            },
        )
    }

    /**
     * The parent changed the apps that skip Kids Garden (FR-038): rebuild the running tunnel
     * in place so the new exclusions apply. Capability stays RUNNING, so no grant ends. Does
     * nothing while the VPN is not running; the next start uses the new list anyway.
     */
    fun reconfigure() {
        if (_capability.value.capability == VpnCapability.RUNNING) launcher.reconfigure()
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
    suspend fun onTunnelEstablished(
        tunFd: Int,
        protector: SocketProtector,
        dns: UpstreamDnsSource,
        exclusions: ExclusionStatus = ExclusionStatus.NONE,
    ): Boolean {
        exclusionStatus = exclusions
        val initial = mutex.withLock { currentProfile() }
        val result = engine.start(EngineConfiguration(), tunFd, protector, dns, initial) { onEngineFailed() }
        return when (result) {
            EngineStartResult.Running -> {
                mutex.withLock {
                    engineRunning = true
                    engine.apply(currentProfile(), latestGeneration, currentGuard())
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

    /** Past its deadline a relaxed policy falls back to the Dull profile it would end in. */
    internal fun currentGuard(): PolicyGuard? {
        val until = latestValidUntil ?: return null
        val table = profiles()
        val fallbackIntensity = (latestPolicy as? NetworkPolicy.SoftLanding)?.target ?: selectedIntensity()
        return PolicyGuard(until, ThrottleProfile.from(table.getValue(fallbackIntensity)))
    }

    private fun report(capability: VpnCapability, reason: VpnUnavailableReason?): VpnCapabilitySnapshot {
        val running = capability == VpnCapability.RUNNING
        val snapshot = VpnCapabilitySnapshot(
            capability = capability,
            reason = if (running) null else reason,
            exclusions = if (running) exclusionStatus else null,
        )
        val wasRunning = _capability.value.capability == VpnCapability.RUNNING
        _capability.value = snapshot
        if (wasRunning && !running) onCapabilityLost?.invoke(reason ?: VpnUnavailableReason.STOPPED)
        return snapshot
    }

    companion object {
        const val DEFAULT_START_TIMEOUT_MS = 20_000L
    }
}
