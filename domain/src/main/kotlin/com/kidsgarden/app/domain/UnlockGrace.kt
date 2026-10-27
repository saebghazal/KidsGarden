package com.kidsgarden.app.domain

/**
 * Unlock grace (FR-037; owner changes 2026-10-25 and 2026-10-27). When the child unlocks the
 * phone with no grant, it works normally for a short grace time, then fades into Dull Mode.
 * The phone looks "tired" rather than obviously configured. No child-facing message.
 *
 * Grace window (clarification 2026-10-27):
 * - The first unlock with no open window opens one, fixed at that moment for the rest time.
 * - The window holds one grace budget (grace + fade). It is used only while the phone is
 *   unlocked: locking banks what is left, and the next unlock in the window continues it.
 * - Unused grace is lost when the window ends; the next unlock after that opens a new window
 *   with a fresh grace.
 * - Turning Kids Garden off and on, and starting Parent Mode, clear the window.
 *
 * While the budget runs it is an ordinary in-memory [ChildGrant] marked
 * [ChildGrant.unlockGrace], so the Soft Landing fade, call pause and expiry rules apply. Only
 * the window start is saved, so a restart cannot reopen a fresh window early; a restarted
 * process treats the rest of that window's budget as used.
 */
object UnlockGrace {

    /** Allowed grace times: 1–15 minutes. */
    val GRACE_SECONDS: List<Int> = listOf(60, 120, 180, 300, 600, 900)

    /** Allowed fade times: 1–5 minutes. */
    val FADE_SECONDS: List<Int> = listOf(60, 120, 180, 240, 300)

    /** Allowed rest times (window length): 15 minutes to 4 hours. */
    val REST_SECONDS: List<Int> = listOf(900, 1800, 3600, 7200, 10800, 14400)

    const val DEFAULT_ENABLED = true
    const val DEFAULT_GRACE_SECONDS = 300
    const val DEFAULT_FADE_SECONDS = 120
    const val DEFAULT_REST_SECONDS = 3600

    /**
     * The window a saved start time implies at ([nowEpoch], [nowElapsed]), or null when it has
     * ended or there is none. A wall clock set back before the start keeps the window open for
     * at most one rest time from now (safe side). Its budget is unknown, so it counts as used.
     */
    fun windowFromSaved(savedStartEpoch: Long?, nowEpoch: Long, nowElapsed: Long, restMillis: Long): GraceWindow? {
        if (savedStartEpoch == null) return null
        val left = if (nowEpoch < savedStartEpoch) restMillis else savedStartEpoch + restMillis - nowEpoch
        if (left <= 0L) return null
        return GraceWindow(savedStartEpoch, nowElapsed + minOf(left, restMillis), remainingMillis = 0L)
    }
}

/**
 * One grace window. [endElapsedMillis] is fixed when it opens. [remainingMillis] is the banked
 * budget while no grace grant runs; while one runs, the budget lives in that grant.
 */
data class GraceWindow(
    val startEpochMillis: Long,
    val endElapsedMillis: Long,
    val remainingMillis: Long,
) {
    fun endedAt(e: Long): Boolean = e >= endElapsedMillis
}
