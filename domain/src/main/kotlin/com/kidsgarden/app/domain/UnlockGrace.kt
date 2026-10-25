package com.kidsgarden.app.domain

/**
 * Unlock grace (FR-037, owner change 2026-10-25). When the child unlocks the phone with no
 * grant, it works normally for a short grace time, then fades into Dull Mode; any unlock
 * within the rest period after a grace started goes straight to Dull Mode. The phone looks
 * "tired" rather than obviously configured. No child-facing message of any kind.
 *
 * The grace is an ordinary in-memory [ChildGrant] marked [ChildGrant.unlockGrace]: duration
 * D = grace + fade and ramp L = fade, so the Soft Landing ramp, call pause, lock invariance
 * and expiry rules all apply unchanged. Only the start time is saved, so a restart cannot
 * hand out a second grace inside the rest period.
 */
object UnlockGrace {

    /** Allowed grace times: 1–15 minutes. */
    val GRACE_SECONDS: List<Int> = listOf(60, 120, 180, 300, 600, 900)

    /** Allowed fade times: 1–5 minutes. */
    val FADE_SECONDS: List<Int> = listOf(60, 120, 180, 240, 300)

    /** Allowed rest periods: 15 minutes to 4 hours. */
    val REST_SECONDS: List<Int> = listOf(900, 1800, 3600, 7200, 10800, 14400)

    const val DEFAULT_ENABLED = true
    const val DEFAULT_GRACE_SECONDS = 300
    const val DEFAULT_FADE_SECONDS = 120
    const val DEFAULT_REST_SECONDS = 3600

    /**
     * True when an unlock at [nowEpoch] may start a grace. A clock set back before the last
     * grace counts as still resting (safe side). [lastStartEpoch] null means never.
     */
    fun restOver(lastStartEpoch: Long?, nowEpoch: Long, restMillis: Long): Boolean {
        if (lastStartEpoch == null) return true
        if (nowEpoch < lastStartEpoch) return false
        return nowEpoch - lastStartEpoch >= restMillis
    }
}
