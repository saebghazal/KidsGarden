package com.kidsgarden.app.effects

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.data.BrightnessRestore
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.EffectPolicy
import com.kidsgarden.app.domain.ScreenEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen and sound effects on a device (T121, FR-034–036). Access is granted and revoked
 * with `appops` so each case starts from a known state. Synthetic values only.
 */
@RunWith(AndroidJUnit4::class)
class ScreenEffectsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val scopes = mutableListOf<CoroutineScope>()

    private var savedMusic = 0
    private var savedRing = 0
    private var savedAlarm = 0
    private var savedBrightness: BrightnessRestore? = null

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(300)
    }

    private fun access(op: String, allow: Boolean) = shell("appops set ${context.packageName} $op ${if (allow) "allow" else "default"}")

    private class MemoryStore(var value: BrightnessRestore? = null) : BrightnessStore {
        override fun current() = value
        override suspend fun save(value: BrightnessRestore?): Boolean {
            this.value = value
            return true
        }
    }

    private fun outputs(overlay: GreyOverlay = GreyOverlay(context)) =
        AndroidEffectOutputs(overlay, MediaVolumeCap(context), BrightnessDimmer(context))

    /** A controller on the main thread with a short build-up and no break. */
    private fun controller(outputs: EffectOutputs, store: BrightnessStore): ScreenEffectsController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scopes += scope
        return ScreenEffectsController(outputs, store, timing = { 1_000L to 0L }, elapsed = { System.nanoTime() / 1_000_000 }, scope = scope)
    }

    private val all = EffectPolicy.On(ScreenEffect.entries.toSet(), DullIntensity.STRONG)

    @Before
    fun saveDeviceState() {
        access("SYSTEM_ALERT_WINDOW", true)
        access("WRITE_SETTINGS", true)
        savedMusic = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        savedRing = audio.getStreamVolume(AudioManager.STREAM_RING)
        savedAlarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        savedBrightness = BrightnessDimmer(context).read()
    }

    @After
    fun restoreDeviceState() {
        scopes.forEach { it.cancel() }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0)
        savedBrightness?.let { BrightnessDimmer(context).restore(it) }
        access("SYSTEM_ALERT_WINDOW", false)
        access("WRITE_SETTINGS", false)
    }

    @Test
    fun overlayPassesTouchesAndCapsAlpha() {
        val overlay = GreyOverlay(context)
        instrumentation.runOnMainSync {
            assertTrue(overlay.show(0.95f))
            assertTrue(overlay.alpha <= GreyOverlay.MAX_ALPHA)
            val passThrough = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            assertEquals(passThrough, overlay.flags and passThrough)
            overlay.hide()
            assertFalse(overlay.showing)
        }
    }

    @Test
    fun noOverlayOnceEffectsAreOffAsDuringACall() = runBlocking {
        val overlay = GreyOverlay(context)
        val c = controller(outputs(overlay), MemoryStore())
        c.apply(all, screenOn = true, generation = 1)
        delay(1_500)
        instrumentation.runOnMainSync { assertTrue(overlay.showing) }

        // The reducer turns effects Off for an active call; everything goes within a second.
        c.apply(EffectPolicy.Off, screenOn = true, generation = 2)
        delay(500)
        instrumentation.runOnMainSync { assertFalse(overlay.showing) }
    }

    @Test
    fun volumeCapIsReappliedAndOtherStreamsUntouched() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        val cap = MediaVolumeCap(context)
        val level = MediaVolumeCap.capLevel(max, 0.15f)

        assertTrue(cap.apply(0.15f))
        assertEquals(level, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

        // Raised during the effect: the next apply brings it back down.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        cap.apply(0.15f)
        assertEquals(level, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

        // A break (fraction 1) lifts the cap without raising volume.
        cap.apply(1f)
        assertEquals(level, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

        assertEquals(savedRing, audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals(savedAlarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun brightnessIsRestoredOnOff() = runBlocking {
        val dimmer = BrightnessDimmer(context)
        val parent = BrightnessRestore(level = 180, automatic = true)
        dimmer.restore(parent)
        val store = MemoryStore()
        val c = controller(outputs(), store)

        c.apply(EffectPolicy.On(setOf(ScreenEffect.LOWER_BRIGHTNESS), DullIntensity.STRONG), screenOn = true, generation = 1)
        delay(1_500)
        assertEquals(parent, store.value)
        val dimmed = dimmer.read()!!
        assertFalse(dimmed.automatic)
        assertTrue(dimmed.level < parent.level)

        c.apply(EffectPolicy.Off, screenOn = true, generation = 2)
        delay(500)
        assertEquals(parent, dimmer.read())
        assertNull(store.value)
    }

    @Test
    fun brightnessIsRestoredAfterProcessDeath() = runBlocking {
        val dimmer = BrightnessDimmer(context)
        // A previous process saved the parent's level, dimmed, and died.
        val parent = BrightnessRestore(level = 150, automatic = false)
        dimmer.dim(parent, 0.2f)
        val store = MemoryStore(parent)

        val c = controller(outputs(), store)
        c.restoreLeftover()
        delay(500)

        assertEquals(parent, dimmer.read())
        assertNull(store.value)
    }

    @Test
    fun missingAccessDisablesOnlyThatEffect() {
        access("SYSTEM_ALERT_WINDOW", false)
        access("WRITE_SETTINGS", false)
        if (Settings.canDrawOverlays(context) || Settings.System.canWrite(context)) return // device grants by default

        val overlay = GreyOverlay(context)
        instrumentation.runOnMainSync { assertFalse(overlay.show(0.5f)) }
        assertFalse(BrightnessDimmer(context).dim(BrightnessRestore(100, false), 0.5f))

        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        assertTrue(MediaVolumeCap(context).apply(0.5f))
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) < max)
    }
}
