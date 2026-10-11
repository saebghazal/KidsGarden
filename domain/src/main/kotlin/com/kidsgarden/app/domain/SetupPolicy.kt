package com.kidsgarden.app.domain

/**
 * Live observations that decide whether setup is complete (FR-019, contracts/android-vpn.md).
 * Built by the platform from current state each time; nothing here is persisted.
 */
data class SetupInputs(
    val credentialPresent: Boolean,
    val onboardingComplete: Boolean,
    /** `VpnService.prepare` returned no consent intent. Not the saved preference. */
    val vpnConsentGranted: Boolean,
    /** The forwarding engine reported real forwarding. */
    val forwardingHealthy: Boolean,
    val notificationsAllowed: Boolean,
    val storageAvailable: Boolean,
)

/** Something that prevents setup from being complete. Call detection is never one. */
enum class SetupBlocker {
    STORAGE_UNAVAILABLE,
    PIN_MISSING,
    VPN_CONSENT_MISSING,
    FORWARDING_UNAVAILABLE,
    ONBOARDING_INCOMPLETE,
}

/** Shown to the parent but never blocks setup, grants, or working dulling. */
enum class SetupLimitation {
    NOTIFICATIONS_DENIED,
}

/** Steps of the first-run flow, in order. */
enum class SetupStep {
    WELCOME,
    PRIVACY,
    CREATE_PIN,
    NOTIFICATIONS,
    VPN,
    DONE,
}

data class SetupStatus(
    val credentialPresent: Boolean,
    val onboardingComplete: Boolean,
    val blockers: Set<SetupBlocker>,
    val limitations: Set<SetupLimitation>,
) {
    val complete: Boolean get() = blockers.isEmpty()
}

/** Pure mapping from live inputs to setup status (T031). */
object SetupPolicy {

    fun evaluate(inputs: SetupInputs): SetupStatus {
        val blockers = buildSet {
            if (!inputs.storageAvailable) add(SetupBlocker.STORAGE_UNAVAILABLE)
            if (!inputs.credentialPresent) add(SetupBlocker.PIN_MISSING)
            if (!inputs.vpnConsentGranted) add(SetupBlocker.VPN_CONSENT_MISSING)
            if (!inputs.forwardingHealthy) add(SetupBlocker.FORWARDING_UNAVAILABLE)
            if (!inputs.onboardingComplete) add(SetupBlocker.ONBOARDING_INCOMPLETE)
        }
        val limitations = buildSet {
            if (!inputs.notificationsAllowed) add(SetupLimitation.NOTIFICATIONS_DENIED)
        }
        return SetupStatus(
            credentialPresent = inputs.credentialPresent,
            onboardingComplete = inputs.onboardingComplete,
            blockers = blockers,
            limitations = limitations,
        )
    }

    /**
     * Where an interrupted setup resumes. Without a PIN it restarts at the welcome screen;
     * with a PIN (after the parent re-enters it) it resumes at the first permission step.
     * Resuming never creates a grant.
     */
    fun resumeStep(status: SetupStatus): SetupStep = when {
        !status.credentialPresent -> SetupStep.WELCOME
        !status.onboardingComplete -> SetupStep.NOTIFICATIONS
        else -> SetupStep.DONE
    }
}
