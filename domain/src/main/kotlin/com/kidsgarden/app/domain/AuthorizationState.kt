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
    val parentAccess: ParentAccess? = null,
    /** Source of unique token and permission identifiers within this process. */
    val nextTokenId: Long = 1L,
    val settings: DomainSettings = DomainSettings(),
    val vpnCapability: VpnCapability = VpnCapability.UNAVAILABLE,
    val callCapability: CallDetectionCapability = CallDetectionCapability.UNAVAILABLE,
    val callState: CallSourceState = CallSourceState.UNAVAILABLE,
    val interactiveAndUnlocked: Boolean = false,
) {
    /** True when setup and live capability allow new grants (Assumptions; FR-019). */
    val setupComplete: Boolean
        get() = settings.credentialPresent && settings.onboardingComplete

    val displayMode: DisplayMode
        get() = when (mode) {
            AuthorizationMode.DULL ->
                if (setupComplete && vpnCapability == VpnCapability.RUNNING) DisplayMode.DULL
                else DisplayMode.SETUP_REQUIRED
            AuthorizationMode.PARENT_MODE -> DisplayMode.PARENT_MODE
            AuthorizationMode.CHILD_PLAY_SESSION -> DisplayMode.CHILD_PLAY_SESSION
            AuthorizationMode.SOFT_LANDING -> DisplayMode.SOFT_LANDING
        }

    companion object {
        fun initial(): AuthorizationState = AuthorizationState()
    }
}
