package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared gradual cycle (T111, FR-035), with fake elapsed time. */
class EffectCycleTest {

    private val buildUp = 120_000L
    private val pause = 60_000L
    private val ease = EffectCycle.EASE_MILLIS
    private val period = buildUp + ease + pause

    private fun s(elapsed: Long, breakMillis: Long = pause) = EffectCycle.strength(elapsed, buildUp, breakMillis)

    @Test
    fun startsFromNone() {
        assertEquals(0f, s(0), 0f)
        assertEquals(0f, s(-5_000), 0f)
    }

    @Test
    fun risesSmoothlyAndMonotonicallyOverBuildUp() {
        var previous = 0f
        for (t in 1_000L..buildUp step 1_000L) {
            val value = s(t)
            assertTrue("t=$t", value >= previous)
            previous = value
        }
        assertEquals(0.5f, s(buildUp / 2), 0.001f)
        // Gentle start: the first second changes almost nothing.
        assertTrue(s(1_000) < 0.01f)
    }

    @Test
    fun reachesFullStrengthAtTheEndOfBuildUp() {
        assertEquals(1f, s(buildUp), 0.001f)
    }

    @Test
    fun easesBackToNoneWithinTenSeconds() {
        assertTrue(s(buildUp + ease / 2) in 0.4f..0.6f)
        assertEquals(0f, s(buildUp + ease), 0.001f)
    }

    @Test
    fun staysAtNoneForTheBreak() {
        for (t in (buildUp + ease)..(period - 1) step 5_000L) assertEquals("t=$t", 0f, s(t), 0f)
    }

    @Test
    fun repeatsAfterTheBreak() {
        assertEquals(0f, s(period), 0.001f)
        assertEquals(s(buildUp / 3), s(period + buildUp / 3), 0.0001f)
        assertEquals(1f, s(2 * period + buildUp), 0.001f)
    }

    @Test
    fun zeroBreakHoldsAtMaximum() {
        assertEquals(0.5f, s(buildUp / 2, breakMillis = 0), 0.001f)
        assertEquals(1f, s(buildUp, breakMillis = 0), 0f)
        assertEquals(1f, s(buildUp + 10 * period, breakMillis = 0), 0f)
    }

    @Test
    fun restartingFromZeroIsAbruptFree() {
        // A restart (screen on, entering DULL) is elapsed time 0 again: none.
        assertEquals(0f, s(0), 0f)
        assertTrue(s(500) < 0.001f)
    }

    @Test
    fun eachAllowedTimingWorks() {
        for (b in EffectCycle.BUILD_UP_SECONDS) for (p in EffectCycle.BREAK_SECONDS) {
            val bm = b * 1_000L
            assertEquals(1f, EffectCycle.strength(bm, bm, p * 1_000L), 0.001f)
        }
    }

    @Test
    fun targetsFollowIntensity() {
        val gentle = EffectTargets.forIntensity(DullIntensity.GENTLE)
        val medium = EffectTargets.forIntensity(DullIntensity.MEDIUM)
        val strong = EffectTargets.forIntensity(DullIntensity.STRONG)
        assertEquals(0.40f, gentle.tintAt(1f), 0f)
        assertEquals(0.60f, medium.tintAt(1f), 0f)
        assertEquals(0.75f, strong.tintAt(1f), 0f)
        assertEquals(0.30f, medium.mediaCapAt(1f), 0.0001f)
        assertEquals(0.20f, strong.brightnessAt(1f), 0.0001f)
        // Strength 0 (a break): no tint, no cap, saved brightness.
        assertEquals(0f, strong.tintAt(0f), 0f)
        assertEquals(1f, strong.mediaCapAt(0f), 0f)
        assertEquals(1f, strong.brightnessAt(0f), 0f)
    }
}
