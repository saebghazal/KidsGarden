package com.kidsgarden.app.domain

import kotlin.math.max

/**
 * One child play grant, held in memory only (data-model.md "Time accounting").
 *
 * D = [durationMillis], L = [rampMillis], e0 = [startElapsedMillis],
 * P = [completedPauseMillis], a = [pauseStartElapsedMillis].
 *
 * - pausedNow = e − a when paused, otherwise 0
 * - Consumed play C = (e−e0)−P−pausedNow
 * - Remaining play R = max(0, D−C)
 * - C < D−L → CHILD_PLAY_SESSION; D−L ≤ C < D → SOFT_LANDING; C ≥ D → DULL
 * - Display projections: expiry = startEpoch+D+P; rampStart = startEpoch+D−L+P
 *
 * All arithmetic is checked. A regressed elapsed clock, overflow, or negative accounting
 * makes the grant invalid, and invalid grants revoke to DULL (FR-013).
 */
data class ChildGrant(
    val durationMillis: Long,
    val rampMillis: Long,
    val startElapsedMillis: Long,
    val startEpochMillis: Long,
    val target: DullIntensity,
    val completedPauseMillis: Long = 0L,
    val pauseStartElapsedMillis: Long? = null,
) {
    init {
        require(durationMillis > 0L) { "duration must be positive" }
        require(rampMillis in 1L until durationMillis) { "ramp must be shorter than duration" }
        require(completedPauseMillis >= 0L) { "pause total must be nonnegative" }
    }

    val isPaused: Boolean
        get() = pauseStartElapsedMillis != null

    /** C at elapsed time [e], or null if the accounting is invalid at [e]. */
    fun consumedMillis(e: Long): Long? {
        if (e < startElapsedMillis) return null
        val pausedNow = when (val a = pauseStartElapsedMillis) {
            null -> 0L
            else -> {
                if (a < startElapsedMillis || e < a) return null
                e - a
            }
        }
        return try {
            val c = Math.subtractExact(
                Math.subtractExact(Math.subtractExact(e, startElapsedMillis), completedPauseMillis),
                pausedNow,
            )
            if (c < 0L) null else c
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** R at [e], never negative; null if invalid. */
    fun remainingMillis(e: Long): Long? = consumedMillis(e)?.let { max(0L, durationMillis - it) }

    /** Authorization implied by consumed play at [e]; null if invalid. */
    fun modeAt(e: Long): AuthorizationMode? = consumedMillis(e)?.let { c ->
        when {
            c < durationMillis - rampMillis -> AuthorizationMode.CHILD_PLAY_SESSION
            c < durationMillis -> AuthorizationMode.SOFT_LANDING
            else -> AuthorizationMode.DULL
        }
    }

    /** Soft Landing progress in [0, 1] at [e]; 0 before the ramp, 1 at or after expiry. */
    fun rampFraction(e: Long): Double? = consumedMillis(e)?.let { c ->
        val rampStart = durationMillis - rampMillis
        when {
            c <= rampStart -> 0.0
            c >= durationMillis -> 1.0
            else -> (c - rampStart).toDouble() / rampMillis.toDouble()
        }
    }

    /** Starts a pause at [e]. Already paused or invalid at [e] → null. */
    fun pausedAt(e: Long): ChildGrant? {
        if (isPaused || consumedMillis(e) == null) return null
        return copy(pauseStartElapsedMillis = e)
    }

    /** Closes the open pause at [e] (P += e − a), checked; not paused or invalid → null. */
    fun resumedAt(e: Long): ChildGrant? {
        val a = pauseStartElapsedMillis ?: return null
        if (e < a) return null
        return try {
            copy(completedPauseMillis = Math.addExact(completedPauseMillis, e - a), pauseStartElapsedMillis = null)
        } catch (_: ArithmeticException) {
            null
        }
    }

    /**
     * Elapsed time of the next mode boundary (ramp start, then expiry) if play continued
     * from [e], or null at or past expiry. While paused this projects from frozen play, so
     * it is the bounded revalidation checkpoint.
     */
    fun nextBoundaryElapsed(e: Long): Long? {
        val c = consumedMillis(e) ?: return null
        val untilNext = when {
            c < durationMillis - rampMillis -> durationMillis - rampMillis - c
            c < durationMillis -> durationMillis - c
            else -> return null
        }
        return try {
            Math.addExact(e, untilNext)
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Parent-facing projected expiry (epoch), excluding any pause still in progress. */
    fun projectedExpiryEpochMillis(): Long? = try {
        Math.addExact(Math.addExact(startEpochMillis, durationMillis), completedPauseMillis)
    } catch (_: ArithmeticException) {
        null
    }

    /** Parent-facing projected ramp start (epoch), excluding any pause still in progress. */
    fun projectedRampStartEpochMillis(): Long? = try {
        Math.addExact(
            Math.addExact(startEpochMillis, durationMillis - rampMillis),
            completedPauseMillis,
        )
    } catch (_: ArithmeticException) {
        null
    }
}
