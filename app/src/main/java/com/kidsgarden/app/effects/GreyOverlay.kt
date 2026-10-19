package com.kidsgarden.app.effects

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * The grey screen (FR-034a): one see-through `TYPE_APPLICATION_OVERLAY` view with no text
 * or content. `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE` let every touch and key pass
 * through, and the window alpha never exceeds what Android allows for pass-through touches
 * (at most 0.8). Application overlays sit below the status bar and system dialogs. Main
 * thread only.
 */
class GreyOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** Highest alpha that still passes touches through on this device. */
    private val maxAlpha: Float by lazy {
        val systemMax = context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: MAX_ALPHA
        minOf(MAX_ALPHA, systemMax)
    }

    /** Shows the tint at [alpha] (clamped); zero removes it. False when access is missing. */
    fun show(alpha: Float): Boolean {
        val clamped = alpha.coerceIn(0f, maxAlpha)
        if (clamped <= 0f) {
            hide()
            return true
        }
        val manager = windowManager
        if (!Settings.canDrawOverlays(context) || manager == null) {
            hide()
            return false
        }
        val current = view
        val currentParams = params
        if (current != null && currentParams != null) {
            if (currentParams.alpha != clamped) {
                currentParams.alpha = clamped
                runCatching { manager.updateViewLayout(current, currentParams) }
            }
            return true
        }
        val newView = View(context).apply {
            setBackgroundColor(Color.GRAY)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val newParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { this.alpha = clamped }
        val added = runCatching { manager.addView(newView, newParams) }.isSuccess
        if (added) {
            view = newView
            params = newParams
        }
        return added
    }

    fun hide() {
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
        view = null
        params = null
    }

    val showing: Boolean get() = view != null

    /** Current window alpha, for tests. */
    val alpha: Float get() = params?.alpha ?: 0f

    /** Window flags, for tests. */
    val flags: Int get() = params?.flags ?: 0

    companion object {
        const val MAX_ALPHA = 0.8f
    }
}
