package com.kidsgarden.app.domain

/** The single calm action that would restore protection, if any. */
enum class SetupRemedy {
    CREATE_PIN,
    FINISH_SETUP,
    GRANT_VPN_CONSENT,
    START_VPN,
    RETRY_STORAGE,
}

/**
 * What the parent dashboard shows about protection (T032). [shapingActive] is true only
 * when the VPN really forwards; a saved preference or granted consent alone is never
 * shown as active protection.
 */
data class ProtectionStatus(
    val displayMode: DisplayMode,
    val shapingActive: Boolean,
    val remedy: SetupRemedy?,
)

object ProtectionStatusMapper {

    fun map(mode: AuthorizationMode, vpn: VpnCapability, setup: SetupStatus): ProtectionStatus {
        val shapingActive = vpn == VpnCapability.RUNNING
        val displayMode = when (mode) {
            AuthorizationMode.DULL ->
                if (setup.complete && shapingActive) DisplayMode.DULL else DisplayMode.SETUP_REQUIRED
            AuthorizationMode.PARENT_MODE -> DisplayMode.PARENT_MODE
            AuthorizationMode.CHILD_PLAY_SESSION -> DisplayMode.CHILD_PLAY_SESSION
            AuthorizationMode.SOFT_LANDING -> DisplayMode.SOFT_LANDING
            AuthorizationMode.DEACTIVATED -> DisplayMode.DEACTIVATED
        }
        return ProtectionStatus(displayMode, shapingActive, remedyFor(setup))
    }

    /** First blocker wins, in the order a parent would fix them. */
    private fun remedyFor(setup: SetupStatus): SetupRemedy? {
        val blockers = setup.blockers
        return when {
            SetupBlocker.STORAGE_UNAVAILABLE in blockers -> SetupRemedy.RETRY_STORAGE
            SetupBlocker.PIN_MISSING in blockers -> SetupRemedy.CREATE_PIN
            SetupBlocker.VPN_CONSENT_MISSING in blockers -> SetupRemedy.GRANT_VPN_CONSENT
            SetupBlocker.FORWARDING_UNAVAILABLE in blockers -> SetupRemedy.START_VPN
            SetupBlocker.ONBOARDING_INCOMPLETE in blockers -> SetupRemedy.FINISH_SETUP
            else -> null
        }
    }
}
