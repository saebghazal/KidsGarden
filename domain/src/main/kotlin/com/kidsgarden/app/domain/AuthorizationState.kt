package com.kidsgarden.app.domain

/**
 * Volatile parent-interface access issued on PIN acceptance. [grantPermission] is a
 * one-use permission for starting a new phone grant; it is consumed by the first
 * explicit action and is absent afterward (FR-007).
 */
data class ParentAccess(
    val token: Long,
    val grantPermission: Long?,
)

/**
 * The reducer's complete state. Lives only in memory: nothing here is persisted
 * (FR-025), so every process start begins from [initial].
 */
data class AuthorizationState(
    val mode: AuthorizationMode = AuthorizationMode.DULL,
    /** Bumped on every grant and revocation; effects carry it so stale results are ignored. */
    val generation: Long = 0L,
    val childGrant: ChildGrant? = null,
    /** Soft Landing stage last applied for [childGrant]; null outside the ramp. */
    val rampStage: Int? = null,
    val parentAccess: ParentAccess? = null,
    /** Source of unique token and permission identifiers within this process. */
    val nextTokenId: Long = 1L,
    val settings: DomainSettings = DomainSettings(),
    val vpnCapability: VpnCapability = VpnCapability.UNAVAILABLE,
    val callCapability: CallDetectionCapability = CallDetectionCapability.UNAVAILABLE,
    val callState: CallSourceState = CallSourceState.UNAVAILABLE,
    /** Latest call-monitor generation seen; older observations are stale. */
    val callMonitorGeneration: Long = Long.MIN_VALUE,
    val interactiveAndUnlocked: Boolean = false,
    /** Screen is on; the effect cycle runs only then (FR-035). */
    val screenOn: Boolean = false,
    /** Live per-effect access; missing entries count as unavailable. */
    val effectAccess: Map<ScreenEffect, EffectAccess> = emptyMap(),
    /** The `active` value being saved for a requested Activate or Deactivate; null when none. */
    val pendingActive: Boolean? = null,
    /** Start of the latest unlock grace in this process (epoch); the saved value covers restarts. */
    val lastUnlockGraceEpochMillis: Long? = null,
) {
    /** True when setup and live capability allow new grants (Assumptions; FR-019). */
    val setupComplete: Boolean
        get() = settings.credentialPresent && settings.onboardingComplete

    /** Enabled effects whose Android access works now. */
    val workingEffects: Set<ScreenEffect>
        get() = settings.enabledEffects.filterTo(mutableSetOf()) { effectAccess[it] == EffectAccess.WORKING }

    /** Some dulling method works: the VPN forwards or an enabled effect has access (FR-032). */
    val dullingCapable: Boolean
        get() = vpnCapability == VpnCapability.RUNNING || workingEffects.isNotEmpty()

    /** Effects run only in DULL (so only while Active) and never during a phone call (FR-034). */
    val effectPolicy: EffectPolicy
        get() {
            val effects = workingEffects
            val on = mode == AuthorizationMode.DULL && callState != CallSourceState.ACTIVE && effects.isNotEmpty()
            return if (on) EffectPolicy.On(effects, settings.dullIntensity) else EffectPolicy.Off
        }

    val displayMode: DisplayMode
        get() = when (mode) {
            AuthorizationMode.DULL ->
                if (setupComplete && dullingCapable) DisplayMode.DULL
                else DisplayMode.SETUP_REQUIRED
            AuthorizationMode.PARENT_MODE -> DisplayMode.PARENT_MODE
            AuthorizationMode.CHILD_PLAY_SESSION -> DisplayMode.CHILD_PLAY_SESSION
            AuthorizationMode.SOFT_LANDING -> DisplayMode.SOFT_LANDING
            AuthorizationMode.DEACTIVATED -> DisplayMode.DEACTIVATED
        }

    companion object {
        fun initial(): AuthorizationState = AuthorizationState()
    }
}
