package com.kidsgarden.app.effects

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.kidsgarden.app.domain.EffectAccess
import com.kidsgarden.app.domain.ScreenEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live per-effect access (FR-036, contracts/screen-effects.md). Read from the system each
 * time; never stored and never a history. Android has no change callback for these
 * accesses, so the app calls [refresh] when it resumes.
 */
class EffectCapabilityProvider(private val context: Context) {

    private val _access = MutableStateFlow(read())
    val access: StateFlow<Map<ScreenEffect, EffectAccess>> = _access.asStateFlow()

    fun refresh() {
        _access.value = read()
    }

    private fun read(): Map<ScreenEffect, EffectAccess> = mapOf(
        ScreenEffect.GREY_SCREEN to
            if (Settings.canDrawOverlays(context)) EffectAccess.WORKING else EffectAccess.ACCESS_MISSING,
        ScreenEffect.LOWER_VOLUME to volumeAccess(),
        ScreenEffect.LOWER_BRIGHTNESS to
            if (Settings.System.canWrite(context)) EffectAccess.WORKING else EffectAccess.ACCESS_MISSING,
    )

    /** Media volume needs no special access, but some devices fix the volume. */
    private fun volumeAccess(): EffectAccess {
        val audio = context.getSystemService(AudioManager::class.java) ?: return EffectAccess.UNAVAILABLE
        return if (audio.isVolumeFixed) EffectAccess.UNAVAILABLE else EffectAccess.WORKING
    }
}
