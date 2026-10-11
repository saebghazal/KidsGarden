package com.kidsgarden.app.calls

import com.kidsgarden.app.domain.CallSourceState

/** One subscription's telephony call state. */
enum class SubscriptionCallState { IDLE, RINGING, OFFHOOK }

/**
 * Pure mapping and aggregation for [TelephonyCallStateMonitor] (T061,
 * contracts/call-state-monitor.md). Its only inputs are per-subscription telephony states,
 * the permission flag, and a monitor generation: there is deliberately no input for
 * third-party app calls or audio mode.
 *
 * Subscription keys are opaque internal handles; they never leave this class.
 */
class TelephonyStateAggregator {

    private val states = HashMap<Int, SubscriptionCallState>()
    private var permissionGranted = false

    var generation: Long = 0L
        private set

    /** Starts a new monitor generation. Cached states, including ACTIVE, are dropped. */
    fun restart(permission: Boolean): Long {
        states.clear()
        permissionGranted = permission
        generation++
        return generation
    }

    /** Records one callback. Callbacks from an older generation are ignored. */
    fun update(key: Int, callbackGeneration: Long, state: SubscriptionCallState): Boolean {
        if (callbackGeneration != generation) return false
        states[key] = state
        return true
    }

    fun remove(key: Int) {
        states.remove(key)
    }

    fun permissionLost() {
        permissionGranted = false
        states.clear()
    }

    /** ACTIVE if any subscription is off-hook; INACTIVE if all are idle or ringing. */
    fun aggregate(): CallSourceState = aggregate(permissionGranted, states.values)

    fun clear() {
        states.clear()
    }

    companion object {
        /** Maps TelephonyManager CALL_STATE_* values; unknown values are treated as idle. */
        fun fromTelephony(value: Int): SubscriptionCallState = when (value) {
            CALL_STATE_RINGING -> SubscriptionCallState.RINGING
            CALL_STATE_OFFHOOK -> SubscriptionCallState.OFFHOOK
            else -> SubscriptionCallState.IDLE
        }

        fun aggregate(permission: Boolean, states: Collection<SubscriptionCallState>): CallSourceState = when {
            !permission -> CallSourceState.UNAVAILABLE
            states.any { it == SubscriptionCallState.OFFHOOK } -> CallSourceState.ACTIVE
            else -> CallSourceState.INACTIVE
        }

        // Values of TelephonyManager.CALL_STATE_RINGING / CALL_STATE_OFFHOOK, kept here so
        // this class stays free of Android types and testable on the JVM.
        const val CALL_STATE_RINGING = 1
        const val CALL_STATE_OFFHOOK = 2
    }
}
