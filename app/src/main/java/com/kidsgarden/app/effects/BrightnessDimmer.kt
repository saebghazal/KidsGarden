package com.kidsgarden.app.effects

import android.content.Context
import android.provider.Settings
import com.kidsgarden.app.data.BrightnessRestore
import kotlin.math.roundToInt

/**
 * Lower brightness (FR-034c) through `Settings.System`, which needs "Modify system
 * settings" access. [ScreenEffectsController] saves the parent's level and automatic setting
 * before the first change; this class only reads and writes the system values.
 */
class BrightnessDimmer(private val context: Context) {

    private var lastMode = -1
    private var lastLevel = -1

    fun canWrite(): Boolean = Settings.System.canWrite(context)

    /** The parent's current brightness, or null if it cannot be read. */
    fun read(): BrightnessRestore? = try {
        val resolver = context.contentResolver
        BrightnessRestore(
            level = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS).coerceIn(0, 255),
            automatic = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
        )
    } catch (_: Settings.SettingNotFoundException) {
        null
    }

    /**
     * Manual mode at [fraction] of [saved]'s level. A fraction of 1 (a break) returns to the
     * saved level while automatic brightness stays paused.
     */
    fun dim(saved: BrightnessRestore, fraction: Float): Boolean =
        write(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, levelFor(saved.level, fraction))

    /** Puts back the parent's level and automatic setting. */
    fun restore(saved: BrightnessRestore): Boolean {
        lastMode = -1
        lastLevel = -1
        val mode = if (saved.automatic) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        // Level first, so automatic mode starts from the parent's level.
        return write(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, saved.level) && write(mode, saved.level)
    }

    /** Writes only what changed, so a running cycle makes few system writes. */
    private fun write(mode: Int, level: Int): Boolean {
        if (!canWrite()) return false
        val resolver = context.contentResolver
        return try {
            if (mode != lastMode) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
                lastMode = mode
            }
            if (level != lastLevel) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
                lastLevel = level
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    companion object {
        /** At least 1, so the screen never goes fully dark. */
        fun levelFor(savedLevel: Int, fraction: Float): Int =
            (savedLevel * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(1, 255)
    }
}
