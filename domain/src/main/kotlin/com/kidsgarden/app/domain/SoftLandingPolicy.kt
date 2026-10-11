package com.kidsgarden.app.domain

/**
 * Soft Landing staging (T069, contracts/state-machine.md "Rates").
 *
 * The ramp covers the last L of a grant's D (L is the captured 60 or 120 seconds). Ramp
 * progress p moves through stages p = 0 (Normal), 1/3, 2/3, and reaches 1 (the captured
 * target) at expiry, when the grant becomes DULL. A stage with p > 0 conceptually caps at
 * K/p and adds p×L of round-trip delay for a target of K kbps and L ms, so every stage is
 * at most as severe as the target: Gentle never reaches Medium or Strong.
 *
 * All stage arithmetic is integer, so a stage and its boundary always agree.
 */
object SoftLandingPolicy {

    /** Stages inside the ramp: 0 = Normal, 1 = p 1/3, 2 = p 2/3. Expiry (p = 1) is DULL. */
    const val STAGES = 3

    /**
     * Stage for consumed play [consumedMillis], or null outside the ramp. The ramp is
     * [D − L, D); at C ≥ D the grant has expired.
     */
    fun stage(durationMillis: Long, rampMillis: Long, consumedMillis: Long): Int? {
        val rampStart = durationMillis - rampMillis
        if (consumedMillis < rampStart || consumedMillis >= durationMillis) return null
        val intoRamp = consumedMillis - rampStart
        return (multiplyOrMax(intoRamp, STAGES.toLong()) / rampMillis).toInt().coerceIn(0, STAGES - 1)
    }

    /** Ramp progress p for a stage, exactly k/3. */
    fun progress(stage: Int): Double = stage.coerceIn(0, STAGES).toDouble() / STAGES

    /**
     * Consumed play at which the next mode or stage boundary occurs after [consumedMillis]:
     * ramp start, each stage start, then expiry. Null at or past expiry.
     */
    fun nextBoundaryConsumed(durationMillis: Long, rampMillis: Long, consumedMillis: Long): Long? {
        val rampStart = durationMillis - rampMillis
        if (consumedMillis >= durationMillis) return null
        if (consumedMillis < rampStart) return rampStart
        val current = stage(durationMillis, rampMillis, consumedMillis) ?: return durationMillis
        val nextStage = current + 1
        if (nextStage >= STAGES) return durationMillis
        // Smallest C with (C − rampStart) × 3 ≥ k × L, i.e. rampStart + ceil(k × L / 3).
        val offset = (nextStage * rampMillis + STAGES - 1) / STAGES
        return rampStart + offset
    }

    /** The staged network intent for [grant] at elapsed [e]; DULL-ward when invalid. */
    fun policy(grant: ChildGrant, e: Long): NetworkPolicy {
        val c = grant.consumedMillis(e) ?: return NetworkPolicy.Dull(grant.target)
        val s = stage(grant.durationMillis, grant.rampMillis, c) ?: return NetworkPolicy.Dull(grant.target)
        return NetworkPolicy.SoftLanding(grant.target, progress(s))
    }

    /** Conceptual cap K/p in kbps for a stage (null = no cap at p = 0). */
    fun stageCapKbps(targetKbps: Double, stage: Int): Double? {
        val p = progress(stage)
        return if (p == 0.0) null else targetKbps / p
    }

    /** Conceptual added round-trip delay p × L in milliseconds for a stage. */
    fun stageAddedRttMillis(targetRttMillis: Long, stage: Int): Long =
        (targetRttMillis * stage.coerceIn(0, STAGES)) / STAGES

    private fun multiplyOrMax(a: Long, b: Long): Long = try {
        Math.multiplyExact(a, b)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}
