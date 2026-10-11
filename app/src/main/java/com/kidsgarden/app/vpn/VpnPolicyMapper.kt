package com.kidsgarden.app.vpn

import com.kidsgarden.app.data.ThrottleSettings
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.NetworkPolicy
import com.kidsgarden.app.domain.SoftLandingPolicy

/** Result of mapping a domain policy for the forwarding engine. */
sealed interface ShapingDecision {
    data class Shape(val profile: ThrottleProfile) : ShapingDecision

    /** The domain could not ask for shaping. Never treated as Normal. */
    data class Unavailable(val reason: String) : ShapingDecision
}

/**
 * Maps domain network policy to scheduler numbers (T039, contracts/state-machine.md).
 *
 * Soft Landing stages come from the domain's [SoftLandingPolicy]: p = 0 is Normal, then
 * p = 1/3 and 2/3, and p = 1 is the target. Each stage caps at K/p and adds p×L RTT, so no
 * stage is ever more severe than the target (Gentle never passes through Medium).
 */
object VpnPolicyMapper {

    fun map(policy: NetworkPolicy, profiles: Map<DullIntensity, ThrottleSettings>): ShapingDecision =
        when (policy) {
            NetworkPolicy.Normal -> ShapingDecision.Shape(ThrottleProfile.UNSHAPED)
            is NetworkPolicy.Dull -> ShapingDecision.Shape(ThrottleProfile.from(profiles.getValue(policy.intensity)))
            is NetworkPolicy.SoftLanding -> ShapingDecision.Shape(softLanding(profiles.getValue(policy.target), policy.fraction))
            is NetworkPolicy.Unavailable -> ShapingDecision.Unavailable(policy.reason)
        }

    /** Stage index 0..3 for a ramp fraction. Invalid fractions resolve to the target, the safe side. */
    fun stageIndex(fraction: Double): Int {
        if (fraction.isNaN() || fraction >= 1.0) return SoftLandingPolicy.STAGES
        if (fraction <= 0.0) return 0
        return kotlin.math.floor(fraction * SoftLandingPolicy.STAGES).toInt().coerceIn(0, SoftLandingPolicy.STAGES)
    }

    /** Stage numbers from [SoftLandingPolicy]: cap K/p and added RTT p×L, split per direction. */
    fun softLanding(target: ThrottleSettings, fraction: Double): ThrottleProfile {
        val stage = stageIndex(fraction)
        if (stage == 0) return ThrottleProfile.UNSHAPED
        if (stage >= SoftLandingPolicy.STAGES) return ThrottleProfile.from(target)
        val capKbps = SoftLandingPolicy.stageCapKbps(target.bandwidthKbps.toDouble(), stage)!!
        val rtt = SoftLandingPolicy.stageAddedRttMillis(target.addedRttLatencyMs.toLong(), stage)
        return ThrottleProfile(
            rateBytesPerSecond = ThrottleProfile.kbpsToBytesPerSecond(capKbps),
            addedDelayPerDirectionMillis = rtt / 2L,
        )
    }
}
