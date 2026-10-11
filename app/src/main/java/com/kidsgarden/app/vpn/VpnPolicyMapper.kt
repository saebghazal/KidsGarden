package com.kidsgarden.app.vpn

import com.kidsgarden.app.data.ThrottleSettings
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.NetworkPolicy
import kotlin.math.roundToLong

/** Result of mapping a domain policy for the forwarding engine. */
sealed interface ShapingDecision {
    data class Shape(val profile: ThrottleProfile) : ShapingDecision

    /** The domain could not ask for shaping. Never treated as Normal. */
    data class Unavailable(val reason: String) : ShapingDecision
}

/**
 * Maps domain network policy to scheduler numbers (T039, contracts/state-machine.md).
 *
 * Soft Landing for target K kbps / L ms and ramp fraction f uses stage p = floor(3f)/3:
 * p = 0 is Normal, then p = 1/3 and 2/3, and p = 1 is the target. Each stage caps at K/p
 * and adds p×L RTT, so no stage is ever more severe than the target (Gentle never passes
 * through Medium).
 */
object VpnPolicyMapper {

    fun map(policy: NetworkPolicy, profiles: Map<DullIntensity, ThrottleSettings>): ShapingDecision =
        when (policy) {
            NetworkPolicy.Normal -> ShapingDecision.Shape(ThrottleProfile.UNSHAPED)
            is NetworkPolicy.Dull -> ShapingDecision.Shape(ThrottleProfile.from(profiles.getValue(policy.intensity)))
            is NetworkPolicy.SoftLanding -> ShapingDecision.Shape(softLanding(profiles.getValue(policy.target), policy.fraction))
            is NetworkPolicy.Unavailable -> ShapingDecision.Unavailable(policy.reason)
        }

    /** Stage p in {0, 1/3, 2/3, 1}. Invalid fractions resolve to the target, the safe side. */
    fun stage(fraction: Double): Double {
        if (fraction.isNaN() || fraction >= 1.0) return 1.0
        if (fraction <= 0.0) return 0.0
        return kotlin.math.floor(fraction * STAGES) / STAGES
    }

    fun softLanding(target: ThrottleSettings, fraction: Double): ThrottleProfile {
        val p = stage(fraction)
        if (p == 0.0) return ThrottleProfile.UNSHAPED
        if (p == 1.0) return ThrottleProfile.from(target)
        val rate = ThrottleProfile.kbpsToBytesPerSecond(target.bandwidthKbps / p)
        val delayRtt = (target.addedRttLatencyMs * p).roundToLong()
        return ThrottleProfile(rateBytesPerSecond = rate, addedDelayPerDirectionMillis = delayRtt / 2L)
    }

    private const val STAGES = 3.0
}
