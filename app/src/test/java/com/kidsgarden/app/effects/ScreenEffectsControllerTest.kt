package com.kidsgarden.app.effects

import com.kidsgarden.app.data.BrightnessRestore
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.EffectPolicy
import com.kidsgarden.app.domain.ScreenEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ScreenEffectsController with fake outputs and virtual time (T115, FR-034–035). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenEffectsControllerTest {

    private class FakeOutputs(var brightness: BrightnessRestore? = BrightnessRestore(level = 200, automatic = true)) : EffectOutputs {
        var tint = 0f
        var volumeFractions = mutableListOf<Float>()
        var dimFraction: Float? = null
        var dimmedFrom: BrightnessRestore? = null
        var restored = mutableListOf<BrightnessRestore>()

        override fun tint(alpha: Float): Boolean {
            tint = alpha
            return true
        }
        override fun capVolume(fraction: Float): Boolean {
            volumeFractions += fraction
            return true
        }
        override fun readBrightness(): BrightnessRestore? = brightness
        override fun dim(saved: BrightnessRestore, fraction: Float): Boolean {
            dimFraction = fraction
            dimmedFrom = saved
            return true
        }
        override fun restoreBrightness(saved: BrightnessRestore): Boolean {
            restored += saved
            dimFraction = null
            return true
        }
    }

    private class FakeStore(var value: BrightnessRestore? = null, var writable: Boolean = true) : BrightnessStore {
        val writes = mutableListOf<BrightnessRestore?>()
        override fun current(): BrightnessRestore? = value
        override suspend fun save(value: BrightnessRestore?): Boolean {
            if (!writable) return false
            writes += value
            this.value = value
            return true
        }
    }

    private val buildUp = 60_000L
    private val pause = 60_000L
    private val all = ScreenEffect.entries.toSet()
    private val on = EffectPolicy.On(all, DullIntensity.MEDIUM)

    private fun TestScope.controller(outputs: FakeOutputs, store: FakeStore): ScreenEffectsController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ScreenEffectsController(
            outputs, store, timing = { buildUp to pause }, elapsed = { testScheduler.currentTime },
            scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher),
        )
    }

    @Test
    fun cycleReachesTargetsAfterBuildUp() = runTest {
        val outputs = FakeOutputs()
        val store = FakeStore()
        val c = controller(outputs, store)

        c.apply(on, screenOn = true, generation = 1)
        runCurrent()
        assertEquals(0f, c.strength, 0f)
        advanceTimeBy(buildUp)
        runCurrent()

        assertEquals(1f, c.strength, 0.01f)
        assertEquals(0.60f, outputs.tint, 0.01f)
        assertEquals(0.30f, outputs.volumeFractions.last(), 0.01f)
        assertEquals(0.40f, outputs.dimFraction!!, 0.01f)
        // The parent's brightness was saved once, before the first change.
        assertEquals(listOf<BrightnessRestore?>(BrightnessRestore(200, true)), store.writes)
    }

    @Test
    fun breakLiftsVolumeCapAndReturnsBrightnessToSavedLevel() = runTest {
        val outputs = FakeOutputs()
        val store = FakeStore()
        val c = controller(outputs, store)
        c.apply(on, screenOn = true, generation = 1)
        advanceTimeBy(buildUp + 10_000L + 20_000L)
        runCurrent()

        assertEquals(0f, c.strength, 0f)
        assertEquals(0f, outputs.tint, 0f)
        assertEquals(1f, outputs.volumeFractions.last(), 0f) // cap lifted, never raised
        assertEquals(1f, outputs.dimFraction!!, 0f) // back at the saved level
        assertEquals(BrightnessRestore(200, true), store.value) // kept until Off
        assertTrue(outputs.restored.isEmpty())
    }

    @Test
    fun offRemovesEverythingAndRestoresBrightness() = runTest {
        val outputs = FakeOutputs()
        val store = FakeStore()
        val c = controller(outputs, store)
        c.apply(on, screenOn = true, generation = 1)
        advanceTimeBy(buildUp)
        runCurrent()

        c.apply(EffectPolicy.Off, screenOn = true, generation = 2)
        runCurrent()

        assertEquals(0f, outputs.tint, 0f)
        assertEquals(listOf(BrightnessRestore(200, true)), outputs.restored)
        assertNull(store.value)
        val renders = outputs.volumeFractions.size
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("no drawing after Off", renders, outputs.volumeFractions.size)
    }

    @Test
    fun screenOffStopsTheCycleAndScreenOnRestartsFromNone() = runTest {
        val outputs = FakeOutputs()
        val c = controller(outputs, FakeStore())
        c.apply(on, screenOn = true, generation = 1)
        advanceTimeBy(buildUp)
        runCurrent()

        c.apply(on, screenOn = false, generation = 1)
        runCurrent()
        assertEquals(0f, c.strength, 0f)
        assertEquals(0f, outputs.tint, 0f)
        val renders = outputs.volumeFractions.size
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("no drawing while the screen is off", renders, outputs.volumeFractions.size)

        c.apply(on, screenOn = true, generation = 1)
        runCurrent()
        assertEquals(0f, c.strength, 0.001f)
        advanceTimeBy(buildUp / 2)
        runCurrent()
        assertEquals(0.5f, c.strength, 0.02f)
    }

    @Test
    fun staleGenerationsAreIgnored() = runTest {
        val outputs = FakeOutputs()
        val c = controller(outputs, FakeStore())
        c.apply(EffectPolicy.Off, screenOn = true, generation = 5)
        c.apply(on, screenOn = true, generation = 4)
        advanceTimeBy(buildUp)
        runCurrent()
        assertEquals(0f, outputs.tint, 0f)
    }

    @Test
    fun leftoverBrightnessIsRestoredAtStartWhenOff() = runTest {
        val outputs = FakeOutputs()
        val store = FakeStore(value = BrightnessRestore(90, automatic = false))
        val c = controller(outputs, store)
        c.restoreLeftover()
        runCurrent()
        assertEquals(listOf(BrightnessRestore(90, false)), outputs.restored)
        assertNull(store.value)
    }

    @Test
    fun leftoverBrightnessIsReusedNotReplacedWhenOn() = runTest {
        val outputs = FakeOutputs(brightness = BrightnessRestore(20, automatic = false)) // still dimmed
        val store = FakeStore(value = BrightnessRestore(180, automatic = true))
        val c = controller(outputs, store)
        c.apply(on, screenOn = true, generation = 1)
        advanceTimeBy(buildUp)
        runCurrent()
        assertEquals(BrightnessRestore(180, true), outputs.dimmedFrom)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun brightnessUntouchedWhenItCannotBeSaved() = runTest {
        val outputs = FakeOutputs()
        val c = controller(outputs, FakeStore(writable = false))
        c.apply(EffectPolicy.On(setOf(ScreenEffect.LOWER_BRIGHTNESS), DullIntensity.STRONG), screenOn = true, generation = 1)
        advanceTimeBy(buildUp)
        runCurrent()
        assertNull(outputs.dimFraction)
    }

    @Test
    fun droppingBrightnessFromTheSetRestoresIt() = runTest {
        val outputs = FakeOutputs()
        val store = FakeStore()
        val c = controller(outputs, store)
        c.apply(on, screenOn = true, generation = 1)
        advanceTimeBy(buildUp)
        runCurrent()
        c.apply(EffectPolicy.On(setOf(ScreenEffect.GREY_SCREEN), DullIntensity.MEDIUM), screenOn = true, generation = 2)
        runCurrent()
        assertEquals(1, outputs.restored.size)
        assertNull(store.value)
    }

    @Test
    fun capLevelsAndBrightnessLevels() {
        assertEquals(4, MediaVolumeCap.capLevel(15, 0.30f))
        assertEquals(2, MediaVolumeCap.capLevel(15, 0.15f))
        assertEquals(15, MediaVolumeCap.capLevel(15, 1f))
        assertEquals(80, BrightnessDimmer.levelFor(200, 0.40f))
        assertEquals(1, BrightnessDimmer.levelFor(2, 0.20f))
    }
}
