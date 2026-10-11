package com.kidsgarden.app.vpn

import com.kidsgarden.app.data.SettingsDocument
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.NetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPolicyMapperTest {

    private val profiles = SettingsDocument.DEFAULT_PROFILES

    private fun profile(policy: NetworkPolicy): ThrottleProfile =
        (VpnPolicyMapper.map(policy, profiles) as ShapingDecision.Shape).profile

    @Test
    fun dullProfilesUseKbpsTimes1000Over8AndHalfRttPerDirection() {
        assertEquals(ThrottleProfile(32_000, 150), profile(NetworkPolicy.Dull(DullIntensity.GENTLE)))
        assertEquals(ThrottleProfile(16_000, 300), profile(NetworkPolicy.Dull(DullIntensity.MEDIUM)))
        assertEquals(ThrottleProfile(8_000, 500), profile(NetworkPolicy.Dull(DullIntensity.STRONG)))
    }

    @Test
    fun normalHasNoCapAndNoDelay() {
        val normal = profile(NetworkPolicy.Normal)
        assertEquals(ThrottleProfile.UNSHAPED, normal)
        assertTrue(normal.unshaped)
    }

    @Test
    fun unavailableIsDistinguishableFromNormal() {
        val decision = VpnPolicyMapper.map(NetworkPolicy.Unavailable("vpn-unavailable"), profiles)
        assertTrue(decision is ShapingDecision.Unavailable)
        assertNotEquals(ShapingDecision.Shape(ThrottleProfile.UNSHAPED), decision)
    }

    @Test
    fun softLandingStagesAreTargetRelative() {
        val target = DullIntensity.MEDIUM // 128 kbps, 600 ms
        assertEquals(ThrottleProfile.UNSHAPED, profile(NetworkPolicy.SoftLanding(target, 0.0)))
        assertEquals(ThrottleProfile.UNSHAPED, profile(NetworkPolicy.SoftLanding(target, 0.2)))
        // p = 1/3: 384 kbps, 200 ms RTT
        assertEquals(ThrottleProfile(48_000, 100), profile(NetworkPolicy.SoftLanding(target, 0.4)))
        // p = 2/3: 192 kbps, 400 ms RTT
        assertEquals(ThrottleProfile(24_000, 200), profile(NetworkPolicy.SoftLanding(target, 0.7)))
        assertEquals(profile(NetworkPolicy.Dull(target)), profile(NetworkPolicy.SoftLanding(target, 1.0)))
    }

    @Test
    fun exactStageBoundariesEnterTheNextStage() {
        val target = DullIntensity.MEDIUM
        assertEquals(ThrottleProfile(48_000, 100), profile(NetworkPolicy.SoftLanding(target, 1.0 / 3.0)))
        assertEquals(ThrottleProfile(24_000, 200), profile(NetworkPolicy.SoftLanding(target, 2.0 / 3.0)))
    }

    @Test
    fun noStageIsEverMoreSevereThanItsTarget() {
        for (intensity in DullIntensity.entries) {
            val target = profile(NetworkPolicy.Dull(intensity))
            for (step in 0..100) {
                val stage = profile(NetworkPolicy.SoftLanding(intensity, step / 100.0))
                val rate = stage.rateBytesPerSecond
                assertTrue(rate == null || rate >= target.rateBytesPerSecond!!)
                assertTrue(stage.addedDelayPerDirectionMillis <= target.addedDelayPerDirectionMillis)
            }
        }
    }

    @Test
    fun gentleNeverPassesThroughMedium() {
        val medium = profile(NetworkPolicy.Dull(DullIntensity.MEDIUM))
        for (step in 0..100) {
            val stage = profile(NetworkPolicy.SoftLanding(DullIntensity.GENTLE, step / 100.0))
            val rate = stage.rateBytesPerSecond
            assertTrue(rate == null || rate > medium.rateBytesPerSecond!!)
            assertTrue(stage.addedDelayPerDirectionMillis < medium.addedDelayPerDirectionMillis)
        }
    }

    @Test
    fun invalidFractionsResolveToTheTarget() {
        val target = profile(NetworkPolicy.Dull(DullIntensity.STRONG))
        assertEquals(target, profile(NetworkPolicy.SoftLanding(DullIntensity.STRONG, Double.NaN)))
        assertEquals(target, profile(NetworkPolicy.SoftLanding(DullIntensity.STRONG, 7.0)))
    }
}
