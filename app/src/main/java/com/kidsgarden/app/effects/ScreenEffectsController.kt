package com.kidsgarden.app.effects

import com.kidsgarden.app.data.BrightnessRestore
import com.kidsgarden.app.domain.EffectCycle
import com.kidsgarden.app.domain.EffectPolicy
import com.kidsgarden.app.domain.EffectPolicySink
import com.kidsgarden.app.domain.EffectTargets
import com.kidsgarden.app.domain.ScreenEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the effects act on. Android implementation in [AndroidEffectOutputs]. */
interface EffectOutputs {
    /** Grey tint at [alpha]; zero removes it. False when it cannot be shown. */
    fun tint(alpha: Float): Boolean

    /** Media volume cap as a fraction of the maximum; 1 lifts the cap. */
    fun capVolume(fraction: Float): Boolean

    fun readBrightness(): BrightnessRestore?

    /** Manual brightness at [fraction] of [saved]'s level. */
    fun dim(saved: BrightnessRestore, fraction: Float): Boolean

    fun restoreBrightness(saved: BrightnessRestore): Boolean
}

/** The saved parent brightness (`SettingsDocument.brightnessRestore`). */
interface BrightnessStore {
    fun current(): BrightnessRestore?

    /** Durably saves or clears ([value] null) the parent's brightness. */
    suspend fun save(value: BrightnessRestore?): Boolean
}

/**
 * Applies [EffectPolicy] from the reducer (T115, contracts/screen-effects.md).
 *
 * - On with the screen on: one shared cycle from none ([EffectCycle]), redrawn every
 *   [tickMillis] only while the screen is on. No wake locks, alarms or background loops.
 * - On with the screen off: the cycle stops and every effect returns to none; it starts
 *   again from none on the next screen on.
 * - Off: tint removed, volume cap lifted, the parent's brightness and automatic setting
 *   restored and the saved value cleared.
 *
 * Brightness: before the first change in a DULL period the parent's level is saved through
 * [BrightnessStore]; if that save fails, brightness is left alone. A value left over from
 * process death is reused rather than replaced, and restored at start when effects are off.
 *
 * Commands are handled one at a time in order on [scope] (the main thread in the app).
 * Stale generations are ignored.
 */
class ScreenEffectsController(
    private val outputs: EffectOutputs,
    private val brightness: BrightnessStore,
    private val timing: () -> Pair<Long, Long>,
    private val elapsed: () -> Long,
    private val scope: CoroutineScope,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
) : EffectPolicySink {

    private sealed interface Command {
        data class Apply(val policy: EffectPolicy, val screenOn: Boolean, val generation: Long) : Command
        data object RestoreLeftover : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var generation = Long.MIN_VALUE
    private var policy: EffectPolicy = EffectPolicy.Off
    private var cycle: Job? = null
    private var cycleStart = 0L
    private var saved: BrightnessRestore? = null

    /** Latest strength drawn, for tests and the parent status only. */
    @Volatile
    var strength: Float = 0f
        private set

    init {
        scope.launch {
            for (command in commands) handle(command)
        }
    }

    override fun apply(policy: EffectPolicy, screenOn: Boolean, generation: Long) {
        commands.trySend(Command.Apply(policy, screenOn, generation))
    }

    /** At process start: restore a brightness left over from process death if effects are off. */
    fun restoreLeftover() {
        commands.trySend(Command.RestoreLeftover)
    }

    private suspend fun handle(command: Command) {
        when (command) {
            Command.RestoreLeftover -> if (policy == EffectPolicy.Off) restoreBrightness()
            is Command.Apply -> {
                if (command.generation < generation) return
                generation = command.generation
                val previous = policy
                policy = command.policy
                when (val next = command.policy) {
                    EffectPolicy.Off -> {
                        stopCycle()
                        outputs.tint(0f)
                        restoreBrightness()
                    }
                    is EffectPolicy.On -> {
                        val hadBrightness = (previous as? EffectPolicy.On)?.enabled?.contains(ScreenEffect.LOWER_BRIGHTNESS) == true
                        if (hadBrightness && ScreenEffect.LOWER_BRIGHTNESS !in next.enabled) restoreBrightness()
                        if (ScreenEffect.GREY_SCREEN !in next.enabled) outputs.tint(0f)
                        if (command.screenOn) {
                            if (cycle?.isActive != true) startCycle()
                        } else {
                            stopCycle()
                            render(next, 0f)
                        }
                    }
                }
            }
        }
    }

    private fun startCycle() {
        cycleStart = elapsed()
        cycle = scope.launch {
            while (isActive) {
                val current = policy as? EffectPolicy.On ?: break
                val (buildUp, pause) = timing()
                render(current, EffectCycle.strength(elapsed() - cycleStart, buildUp, pause))
                delay(tickMillis)
            }
        }
    }

    private fun stopCycle() {
        cycle?.cancel()
        cycle = null
        strength = 0f
    }

    private suspend fun render(on: EffectPolicy.On, value: Float) {
        strength = value
        val targets = EffectTargets.forIntensity(on.intensity)
        if (ScreenEffect.GREY_SCREEN in on.enabled) outputs.tint(targets.tintAt(value))
        if (ScreenEffect.LOWER_VOLUME in on.enabled) outputs.capVolume(targets.mediaCapAt(value))
        if (ScreenEffect.LOWER_BRIGHTNESS in on.enabled) {
            val restoreTo = saved ?: brightness.current()?.also { saved = it } ?: if (value > 0f) saveCurrent() else null
            if (restoreTo != null) outputs.dim(restoreTo, targets.brightnessAt(value))
        }
    }

    /** Saves the parent's brightness before the first change; null if it cannot be saved. */
    private suspend fun saveCurrent(): BrightnessRestore? {
        val current = outputs.readBrightness() ?: return null
        if (!brightness.save(current)) return null
        saved = current
        return current
    }

    private suspend fun restoreBrightness() {
        val value = saved ?: brightness.current() ?: return
        if (outputs.restoreBrightness(value) && brightness.save(null)) saved = null
    }

    companion object {
        const val DEFAULT_TICK_MILLIS = 100L
    }
}

/** Default timings in milliseconds, for callers before settings load. */
fun defaultEffectTiming(): Pair<Long, Long> =
    EffectCycle.DEFAULT_BUILD_UP_SECONDS * 1_000L to EffectCycle.DEFAULT_BREAK_SECONDS * 1_000L

/** Android outputs: overlay, media volume and brightness adapters. Main thread. */
class AndroidEffectOutputs(
    private val overlay: GreyOverlay,
    private val volume: MediaVolumeCap,
    private val dimmer: BrightnessDimmer,
) : EffectOutputs {
    override fun tint(alpha: Float): Boolean = overlay.show(alpha)
    override fun capVolume(fraction: Float): Boolean = volume.apply(fraction)
    override fun readBrightness(): BrightnessRestore? = dimmer.read()
    override fun dim(saved: BrightnessRestore, fraction: Float): Boolean = dimmer.dim(saved, fraction)
    override fun restoreBrightness(saved: BrightnessRestore): Boolean = dimmer.restore(saved)
}
