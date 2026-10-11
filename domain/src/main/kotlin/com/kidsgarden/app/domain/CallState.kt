package com.kidsgarden.app.domain

/**
 * Aggregated telephony call state (contracts/call-state-monitor.md). Only normal
 * mobile/Telecom calls are observed; there is deliberately no input for third-party
 * app calls or audio mode.
 */
enum class CallSourceState {
    /** At least one subscription reports an off-hook phone call. */
    ACTIVE,

    /** All subscriptions idle or ringing. Never means "no call of any kind". */
    INACTIVE,

    /** Permission missing, registration lost, or query failed. Countdown continues. */
    UNAVAILABLE,
}

/** One observation. Contains no number, contact, SIM identifier, or app identity. */
data class CallObservation(
    val state: CallSourceState,
    val monitorGeneration: Long,
    val elapsedMillis: Long,
)

/** Whether call pause can work at all. Independent of VPN readiness and setup. */
enum class CallDetectionCapability {
    AVAILABLE,
    PERMISSION_MISSING,
    UNSUPPORTED,
    UNAVAILABLE,
}
