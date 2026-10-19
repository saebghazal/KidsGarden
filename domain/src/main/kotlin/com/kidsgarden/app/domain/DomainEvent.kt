package com.kidsgarden.app.domain

/**
 * Typed inputs to [StateMachine.reduce] (contracts/state-machine.md). Events never carry
 * raw PINs, Android types, intent extras, or any call/content identity.
 */
sealed interface DomainEvent {
    /**
     * A new process. [savedActive] is the valid saved `active` setting; false starts at
     * DEACTIVATED (FR-031). Missing or invalid settings always mean true.
     */
    data class ProcessStarted(val savedActive: Boolean = true) : DomainEvent
    data object RebootObserved : DomainEvent
    data object PackageReplaced : DomainEvent

    data object UiOpened : DomainEvent
    data object ParentUiLeft : DomainEvent

    /** PIN verification succeeded. The reducer issues opaque tokens; no PIN is passed. */
    data object PinVerified : DomainEvent
    data object PinRejected : DomainEvent

    /** The new PIN was durably saved. Every grant and all parent access end. */
    data object PinChanged : DomainEvent

    /** The parent is turning VPN dulling off. Grants end before the VPN stops. */
    data object VpnStopRequested : DomainEvent

    /** [grantPermission] must be the unused one-use permission issued on PIN entry. */
    data class StartParentRequested(val grantPermission: Long) : DomainEvent
    data class StartChildRequested(val grantPermission: Long, val durationMinutes: Int) : DomainEvent
    data object ManualParentExit : DomainEvent

    /** Confirmed Deactivate from the parent interface (FR-031). Commits only after the save. */
    data class DeactivateRequested(val confirmed: Boolean) : DomainEvent

    /** Activate with the fresh one-use permission from the PIN entry. Commits only after the save. */
    data class ActivateRequested(val grantPermission: Long) : DomainEvent

    /** Outcome of saving `active`, reported by SessionManager after a requested switch. */
    data class ActiveWriteSucceeded(val active: Boolean) : DomainEvent
    data class ActiveWriteFailed(val active: Boolean) : DomainEvent

    data object ScreenOn : DomainEvent
    data object ScreenOff : DomainEvent
    data object DeviceLocked : DomainEvent
    data object UserPresent : DomainEvent

    data class CallAggregateChanged(val observation: CallObservation) : DomainEvent

    /** Result of a fresh current-state query; the only observation that can renew a pause. */
    data class CallReconciled(val observation: CallObservation) : DomainEvent
    data class CallCapabilityChanged(val capability: CallDetectionCapability) : DomainEvent

    /** A scheduled boundary (or any wake opportunity) fired; the reducer re-evaluates. */
    data class DeadlineObserved(val boundaryGeneration: Long) : DomainEvent

    data class SettingsChanged(val settings: DomainSettings) : DomainEvent
    data object SettingsInvalid : DomainEvent
    data object SettingsWriteSucceeded : DomainEvent
    data object SettingsWriteFailed : DomainEvent

    data class VpnCapabilityChanged(val capability: VpnCapability) : DomainEvent

    /** Live per-effect access (FR-036). Missing access disables only that effect. */
    data class EffectCapabilityChanged(val access: Map<ScreenEffect, EffectAccess>) : DomainEvent

    /** The parent changed the four dulling-method switches (FR-036). */
    data class MethodSwitchesChanged(val networkEnabled: Boolean, val effects: Set<ScreenEffect>) : DomainEvent

    /** Device interactivity sampled by the platform (screen on and unlocked). */
    data class InteractivityObserved(val interactiveAndUnlocked: Boolean) : DomainEvent
}

/** The slice of persisted parent settings the domain needs. No authorization data. */
data class DomainSettings(
    val dullIntensity: DullIntensity = DullIntensity.MEDIUM,
    val softLandingDurationSeconds: Int = 120,
    val credentialPresent: Boolean = false,
    val onboardingComplete: Boolean = false,
    /** Parent Active/Deactivated choice (FR-031). Only read at process start. */
    val active: Boolean = true,
    /** Network dulling switch (`vpnDullingEnabled`). */
    val networkEnabled: Boolean = true,
    /** Enabled screen and sound effects (FR-036). */
    val enabledEffects: Set<ScreenEffect> = emptySet(),
)

/** Live shaping capability reported by the VPN controller. A saved preference is not proof. */
enum class VpnCapability {
    /** Consent missing, service stopped, revoked, another VPN active, or engine failed. */
    UNAVAILABLE,
    STARTING,

    /** Forwarding is operational and policies can be applied. */
    RUNNING,
}

/** Network intent computed by the domain and applied by the VPN controller. */
sealed interface NetworkPolicy {
    /** No intentional cap or delay. Only for valid Parent Mode or early child play. */
    data object Normal : NetworkPolicy

    data class Dull(val intensity: DullIntensity) : NetworkPolicy

    /** [fraction] is ramp progress in [0, 1]; stages are applied by SoftLandingPolicy. */
    data class SoftLanding(val target: DullIntensity, val fraction: Double) : NetworkPolicy

    /** Shaping impossible; traffic is unshaped and the parent sees limited protection. */
    data class Unavailable(val reason: String) : NetworkPolicy
}

/**
 * Outputs of [StateMachine.reduce]. Every effect carries the authorization generation it
 * was computed for, so stale acknowledgments can be ignored.
 */
sealed interface Effect {
    val generation: Long

    /** [validUntilElapsedMillis] is the next grant boundary for relaxed policies, else null. */
    data class ApplyNetworkPolicy(
        val policy: NetworkPolicy,
        override val generation: Long,
        val validUntilElapsedMillis: Long? = null,
    ) : Effect
    data class ScheduleBoundary(val atElapsedMillis: Long, override val generation: Long) : Effect
    data class CancelBoundary(override val generation: Long) : Effect
    data class ClearParentUiAccess(override val generation: Long) : Effect

    /** Query current call state now and report it as [DomainEvent.CallReconciled]. */
    data class ReconcileCalls(override val generation: Long) : Effect
    data class PublishParentStatus(override val generation: Long) : Effect

    /** Save the Active/Deactivated choice; SessionManager reports the outcome as an event. */
    data class PersistActive(val active: Boolean, override val generation: Long) : Effect

    /** Start VPN dulling (Activate). Starting still needs live consent. */
    data class StartVpn(override val generation: Long) : Effect

    /** Stop VPN dulling and its foreground service (Deactivate). */
    data class StopVpn(override val generation: Long) : Effect

    /** Screen and sound effects; [screenOn] lets the platform stop or restart the cycle. */
    data class ApplyEffectPolicy(val policy: EffectPolicy, val screenOn: Boolean, override val generation: Long) : Effect
}
