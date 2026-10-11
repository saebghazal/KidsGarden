package com.kidsgarden.app.domain

import com.kidsgarden.app.domain.ReducerHarness.Companion.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Soft Landing staging (T069, T070, T071). */
class SoftLandingPolicyTest {

    /** Initial profile numbers (kbps, added RTT ms) from data-model.md. */
    private val profiles = mapOf(
        DullIntensity.GENTLE to (256.0 to 300L),
        DullIntensity.MEDIUM to (128.0 to 600L),
        DullIntensity.STRONG to (64.0 to 1000L),
    )

    private fun harness(rampSeconds: Int = 120, intensity: DullIntensity = DullIntensity.MEDIUM): ReducerHarness =
        ReducerHarness().apply {
            state = state.copy(settings = state.settings.copy(softLandingDurationSeconds = rampSeconds, dullIntensity = intensity))
        }

    private fun ReducerHarness.rampPolicy(): NetworkPolicy.SoftLanding = lastPolicy() as NetworkPolicy.SoftLanding

    @Test
    fun threeIncreasingStagesForEachIntensityNeverPastTheTarget() {
        for ((intensity, numbers) in profiles) {
            val (kbps, rtt) = numbers
            var previousCap = Double.POSITIVE_INFINITY
            var previousRtt = -1L
            for (stage in 0 until SoftLandingPolicy.STAGES) {
                val cap = SoftLandingPolicy.stageCapKbps(kbps, stage) ?: Double.POSITIVE_INFINITY
                val added = SoftLandingPolicy.stageAddedRttMillis(rtt, stage)
                // Stage 0 is Normal (uncapped); every later stage tightens the cap.
                if (stage == 0) {
                    assertNull(SoftLandingPolicy.stageCapKbps(kbps, stage))
                } else {
                    assertTrue("$intensity stage $stage cap", cap < previousCap)
                }
                assertTrue("$intensity stage $stage rtt", added > previousRtt)
                assertTrue(cap >= kbps)
                assertTrue(added <= rtt)
                previousCap = cap
                previousRtt = added
            }
        }
    }

    @Test
    fun gentleNeverReachesMediumOrStrong() {
        val (gentleKbps, gentleRtt) = profiles.getValue(DullIntensity.GENTLE)
        val (mediumKbps, mediumRtt) = profiles.getValue(DullIntensity.MEDIUM)
        for (stage in 0..SoftLandingPolicy.STAGES) {
            val cap = SoftLandingPolicy.stageCapKbps(gentleKbps, stage) ?: Double.POSITIVE_INFINITY
            assertTrue(cap > mediumKbps)
            assertTrue(SoftLandingPolicy.stageAddedRttMillis(gentleRtt, stage) < mediumRtt)
        }
    }

    @Test
    fun rampWalksNormalThenOneThirdThenTwoThirdsThenDull() {
        val h = harness()
        h.startChild(10)
        val seen = mutableListOf<NetworkPolicy>()
        repeat(4) {
            h.clock.elapsed = h.lastBoundary()!!
            h.fireBoundary()
            seen += h.lastPolicy()!!
        }
        assertEquals(
            listOf(
                NetworkPolicy.SoftLanding(DullIntensity.MEDIUM, 0.0),
                NetworkPolicy.SoftLanding(DullIntensity.MEDIUM, 1.0 / 3.0),
                NetworkPolicy.SoftLanding(DullIntensity.MEDIUM, 2.0 / 3.0),
                NetworkPolicy.Dull(DullIntensity.MEDIUM),
            ),
            seen,
        )
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun exactBoundaryInstantsForTwoMinuteRamp() {
        val d = 10 * MINUTE
        val l = 120_000L
        val rampStart = d - l
        assertEquals(rampStart, SoftLandingPolicy.nextBoundaryConsumed(d, l, 0))
        assertEquals(rampStart + 40_000, SoftLandingPolicy.nextBoundaryConsumed(d, l, rampStart))
        assertEquals(rampStart + 80_000, SoftLandingPolicy.nextBoundaryConsumed(d, l, rampStart + 40_000))
        assertEquals(d, SoftLandingPolicy.nextBoundaryConsumed(d, l, rampStart + 80_000))
        assertNull(SoftLandingPolicy.nextBoundaryConsumed(d, l, d))

        assertEquals(0, SoftLandingPolicy.stage(d, l, rampStart + 39_999))
        assertEquals(1, SoftLandingPolicy.stage(d, l, rampStart + 40_000))
        assertEquals(1, SoftLandingPolicy.stage(d, l, rampStart + 79_999))
        assertEquals(2, SoftLandingPolicy.stage(d, l, rampStart + 80_000))
        assertEquals(2, SoftLandingPolicy.stage(d, l, d - 1))
        assertNull(SoftLandingPolicy.stage(d, l, d))
        assertNull(SoftLandingPolicy.stage(d, l, rampStart - 1))
    }

    @Test
    fun oneMinuteRampUsesTwentySecondStages() {
        val h = harness(rampSeconds = 60)
        val start = h.clock.elapsed
        h.startChild(10)
        assertEquals(start + 10 * MINUTE - 60_000, h.lastBoundary())
        h.clock.elapsed = h.lastBoundary()!!
        h.fireBoundary()
        assertEquals(start + 10 * MINUTE - 40_000, h.lastBoundary())
        h.clock.elapsed = h.lastBoundary()!!
        h.fireBoundary()
        assertEquals(1.0 / 3.0, h.rampPolicy().fraction, 0.0)
        assertEquals(start + 10 * MINUTE - 20_000, h.lastBoundary())
    }

    @Test
    fun delayedWakeSkippingStagesLandsOnTheCurrentOne() {
        val h = harness()
        val start = h.clock.elapsed
        h.startChild(10)
        h.clock.elapsed = start + 10 * MINUTE - 30_000 // stage 2, two stages skipped
        val result = h.send(DomainEvent.UserPresent)
        val policies = result.effects.filterIsInstance<Effect.ApplyNetworkPolicy>().map { it.policy }
        assertEquals(listOf(NetworkPolicy.SoftLanding(DullIntensity.MEDIUM, 2.0 / 3.0)), policies)
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)
        assertEquals(start + 10 * MINUTE, h.lastBoundary())
    }

    @Test
    fun pausedRampWithFortyFiveSecondsLeftResumesWithoutReplay() {
        val h = harness()
        val start = h.clock.elapsed
        h.startChild(10)
        h.clock.elapsed = start + 10 * MINUTE - 45_000 // 75 s into a 120 s ramp: stage 1
        h.fireBoundary()
        assertEquals(1.0 / 3.0, h.rampPolicy().fraction, 0.0)

        h.call(CallSourceState.ACTIVE)
        h.advanceMinutes(12)
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)
        assertEquals(1.0 / 3.0, h.rampPolicy().fraction, 0.0) // frozen

        h.call(CallSourceState.INACTIVE)
        assertEquals(45_000L, h.remaining())
        assertEquals(1.0 / 3.0, h.rampPolicy().fraction, 0.0) // same stage, not replayed from 0
        assertEquals(h.clock.elapsed + 5_000, h.lastBoundary()) // stage 2 at 80 s into the ramp
    }

    @Test
    fun intensityChangeMidSessionWaitsForTheNextGrant() {
        val h = harness()
        h.startChild(10)
        h.send(DomainEvent.SettingsChanged(h.state.settings.copy(dullIntensity = DullIntensity.STRONG)))
        h.clock.elapsed = h.lastBoundary()!!
        h.fireBoundary()
        h.clock.elapsed = h.lastBoundary()!!
        h.fireBoundary()
        assertEquals(DullIntensity.MEDIUM, h.rampPolicy().target)
        repeat(2) {
            h.clock.elapsed = h.lastBoundary()!!
            h.fireBoundary()
        }
        // After expiry DULL uses the newly selected intensity.
        assertEquals(NetworkPolicy.Dull(DullIntensity.STRONG), h.lastPolicy())
    }

    @Test
    fun rampAndExpiryProduceNoParentOrChildInterfaceEffects() {
        val h = harness()
        h.startChild(10)
        h.effects.clear()
        repeat(4) {
            h.clock.elapsed = h.lastBoundary() ?: return@repeat
            h.fireBoundary()
        }
        val allowed = setOf(
            Effect.ApplyNetworkPolicy::class,
            Effect.ScheduleBoundary::class,
            Effect.CancelBoundary::class,
            Effect.PublishParentStatus::class,
        )
        assertTrue(h.effects.all { it::class in allowed })
    }
}
