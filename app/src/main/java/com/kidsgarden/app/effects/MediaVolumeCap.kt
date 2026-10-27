package com.kidsgarden.app.effects

import android.content.Context
import android.media.AudioManager
import kotlin.math.floor

/**
 * Lower media volume (FR-034b). `STREAM_MUSIC` only: call, ring, alarm, notification and
 * accessibility volumes are never read or changed. The cap only ever lowers: if the media
 * volume is above the cap it is set to the cap, which also re-applies the cap when someone
 * raises it during the effect. A fraction of 1 (a break, or effects off) lifts the cap and
 * changes nothing, so Kids Garden never raises volume itself.
 */
class MediaVolumeCap(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    /** Caps media at [capFraction] of the stream maximum; false if the volume cannot be set. */
    fun apply(capFraction: Float): Boolean {
        val manager = audio ?: return false
        if (capFraction >= 1f) return true
        val cap = capLevel(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), capFraction)
        return try {
            if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0)
            }
            true
        } catch (_: SecurityException) {
            false // for example a Do Not Disturb policy on some devices
        }
    }

    companion object {
        /** Highest allowed media level for [fraction] of [max]; never below zero. */
        fun capLevel(max: Int, fraction: Float): Int = floor(max * fraction.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
    }
}
