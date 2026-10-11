package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChildGrantTest {

    private val minute = 60_000L
    private val e0 = 1_000_000L

    private fun grant(pauseTotal: Long = 0L, pauseStart: Long? = null) = ChildGrant(
        durationMillis = 30 * minute,
        rampMillis = 2 * minute,
        startElapsedMillis = e0,
        startEpochMillis = 1_760_000_000_000L,
        target = DullIntensity.MEDIUM,
        completedPauseMillis = pauseTotal,
        pauseStartElapsedMillis = pauseStart,
    )

    @Test
    fun thirtyFiveTwentyFiveExampleLeavesTwentyFiveMinutes() {
        // 30-minute grant, 5 minutes played, then a 25-minute call (data-model.md).
        val g = grant(pauseTotal = 25 * minute)
        val e = e0 + 30 * minute
        assertEquals(5 * minute, g.consumedMillis(e))
        assertEquals(25 * minute, g.remainingMillis(e))
    }

    @Test
    fun timeIsFrozenWhilePaused() {
        val pauseAt = e0 + 5 * minute
        val g = grant(pauseStart = pauseAt)
        assertEquals(5 * minute, g.consumedMillis(pauseAt + 10 * minute))
        assertEquals(5 * minute, g.consumedMillis(pauseAt + 25 * minute))
    }

    @Test
    fun modeBoundariesFollowConsumedPlay() {
        val g = grant()
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, g.modeAt(e0 + 28 * minute - 1))
        assertEquals(AuthorizationMode.SOFT_LANDING, g.modeAt(e0 + 28 * minute))
        assertEquals(AuthorizationMode.SOFT_LANDING, g.modeAt(e0 + 30 * minute - 1))
        assertEquals(AuthorizationMode.DULL, g.modeAt(e0 + 30 * minute))
    }

    @Test
    fun remainingIsNeverNegative() {
        assertEquals(0L, grant().remainingMillis(e0 + 90 * minute))
    }

    @Test
    fun rampFractionProgressesAcrossSoftLanding() {
        val g = grant()
        assertEquals(0.0, g.rampFraction(e0 + 28 * minute)!!, 0.0)
        assertEquals(0.5, g.rampFraction(e0 + 29 * minute)!!, 1e-9)
        assertEquals(1.0, g.rampFraction(e0 + 30 * minute)!!, 0.0)
    }

    @Test
    fun regressedClockIsInvalid() {
        assertNull(grant().consumedMillis(e0 - 1))
        assertNull(grant(pauseStart = e0 + minute).consumedMillis(e0 + minute - 1))
    }

    @Test
    fun overflowIsInvalid() {
        val g = grant(pauseTotal = Long.MAX_VALUE)
        assertNull(g.consumedMillis(e0 + minute))
    }

    @Test
    fun projectionsShiftByCompletedPause() {
        val g = grant(pauseTotal = 25 * minute)
        assertEquals(g.startEpochMillis + 55 * minute, g.projectedExpiryEpochMillis())
        assertEquals(g.startEpochMillis + 53 * minute, g.projectedRampStartEpochMillis())
    }
}
