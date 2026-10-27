package com.kidsgarden.app.domain

/**
 * Screen and sound dulling (FR-034–036, contracts/screen-effects.md). Effects are part of
 * Dull Mode, never an authorization. Named [ScreenEffect] because [Effect] is the
 * reducer's output type.
 */
enum class ScreenEffect {
    GREY_SCREEN,
    LOWER_VOLUME,
    LOWER_BRIGHTNESS,
}

/** Live per-effect capability. Runtime only; never a history. */
enum class EffectAccess {
    WORKING,

    /** Android access ("Display over other apps", "Modify system settings") is not granted. */
    ACCESS_MISSING,
    UNAVAILABLE,
}

/** What the effects should do now. Produced only by the reducer. */
sealed interface EffectPolicy {
    data object Off : EffectPolicy

    /** [enabled] is never empty; an empty set is [Off]. */
    data class On(val enabled: Set<ScreenEffect>, val intensity: DullIntensity) : EffectPolicy
}

/**
 * Maximum strength per intensity (FR-034). Fixed in code, never stored.
 *
 * - [tintAlpha]: grey tint opacity at full strength.
 * - [mediaCapFraction]: media volume cap as a fraction of the stream maximum.
 * - [brightnessFraction]: brightness as a fraction of the parent's saved level.
 */
data class EffectTargets(
    val tintAlpha: Float,
    val mediaCapFraction: Float,
    val brightnessFraction: Float,
) {
    /** Tint opacity at [strength] (0…1). */
    fun tintAt(strength: Float): Float = tintAlpha * strength.coerceIn(0f, 1f)

    /** Media volume cap at [strength], as a fraction of the maximum: 1 means no cap. */
    fun mediaCapAt(strength: Float): Float = 1f - strength.coerceIn(0f, 1f) * (1f - mediaCapFraction)

    /** Brightness at [strength], as a fraction of the saved level: 1 means unchanged. */
    fun brightnessAt(strength: Float): Float = 1f - strength.coerceIn(0f, 1f) * (1f - brightnessFraction)

    companion object {
        fun forIntensity(intensity: DullIntensity): EffectTargets = when (intensity) {
            DullIntensity.GENTLE -> EffectTargets(tintAlpha = 0.40f, mediaCapFraction = 0.50f, brightnessFraction = 0.60f)
            DullIntensity.MEDIUM -> EffectTargets(tintAlpha = 0.60f, mediaCapFraction = 0.30f, brightnessFraction = 0.40f)
            DullIntensity.STRONG -> EffectTargets(tintAlpha = 0.75f, mediaCapFraction = 0.15f, brightnessFraction = 0.20f)
        }
    }
}

/**
 * The shared gradual cycle (FR-035): rise smoothly over build-up, ease back to none within
 * [EASE_MILLIS], stay at none for the break, repeat. A zero break holds full strength after
 * the first build-up. Pure and deterministic; elapsed time is measured from cycle start.
 */
object EffectCycle {

    const val EASE_MILLIS = 10_000L

    /** Allowed build-up times (FR-035): 1–5 minutes. */
    val BUILD_UP_SECONDS: List<Int> = listOf(60, 120, 180, 240, 300)

    /** Allowed break times (FR-035): 0–5 minutes; 0 holds at maximum. */
    val BREAK_SECONDS: List<Int> = listOf(0, 60, 120, 180, 240, 300)

    const val DEFAULT_BUILD_UP_SECONDS = 120
    const val DEFAULT_BREAK_SECONDS = 60

    fun strength(elapsedMillis: Long, buildUpMillis: Long, breakMillis: Long): Float {
        require(buildUpMillis > 0) { "build-up must be positive" }
        require(breakMillis >= 0) { "break must not be negative" }
        if (elapsedMillis <= 0) return 0f
        if (breakMillis == 0L) {
            return if (elapsedMillis >= buildUpMillis) 1f else smooth(elapsedMillis.toFloat() / buildUpMillis)
        }
        val t = elapsedMillis % (buildUpMillis + EASE_MILLIS + breakMillis)
        return when {
            t < buildUpMillis -> smooth(t.toFloat() / buildUpMillis)
            t < buildUpMillis + EASE_MILLIS -> 1f - smooth((t - buildUpMillis).toFloat() / EASE_MILLIS)
            else -> 0f
        }
    }

    /** Smoothstep: starts and ends gently, so no change is abrupt. */
    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}
