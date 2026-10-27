package com.kidsgarden.app.ui.dashboard

import com.kidsgarden.app.R
import com.kidsgarden.app.domain.EffectAccess
import com.kidsgarden.app.domain.ParentFacingSnapshot
import com.kidsgarden.app.domain.ScreenEffect
import com.kidsgarden.app.domain.VpnCapability
import com.kidsgarden.app.vpn.VpnUnavailableReason

/** The four dulling methods the parent can switch (FR-036). */
enum class DullingMethod(val label: Int) {
    NETWORK(R.string.method_network),
    GREY(R.string.method_grey),
    VOLUME(R.string.method_volume),
    BRIGHTNESS(R.string.method_brightness),
}

/** Whether a method actually works now. Live state only; never a history. */
enum class MethodState(val label: Int) {
    WORKING(R.string.method_working),
    OFF(R.string.method_off),
    ACCESS_MISSING(R.string.method_access_missing),
    UNAVAILABLE(R.string.method_unavailable),
    NOT_RUNNING(R.string.method_not_running),
}

data class MethodStatus(val method: DullingMethod, val state: MethodState)

fun DullingMethod.effect(): ScreenEffect? = when (this) {
    DullingMethod.NETWORK -> null
    DullingMethod.GREY -> ScreenEffect.GREY_SCREEN
    DullingMethod.VOLUME -> ScreenEffect.LOWER_VOLUME
    DullingMethod.BRIGHTNESS -> ScreenEffect.LOWER_BRIGHTNESS
}

/** Per-method status for the dashboard and Settings (FR-036 "which methods are actually working"). */
fun methodStatuses(snapshot: ParentFacingSnapshot): List<MethodStatus> = DullingMethod.entries.map { method ->
    val effect = method.effect()
    val state = if (effect == null) {
        when {
            !snapshot.networkEnabled -> MethodState.OFF
            snapshot.vpnCapability == VpnCapability.RUNNING -> MethodState.WORKING
            else -> MethodState.NOT_RUNNING
        }
    } else {
        when {
            effect !in snapshot.enabledEffects -> MethodState.OFF
            snapshot.effectAccess[effect] == EffectAccess.WORKING -> MethodState.WORKING
            snapshot.effectAccess[effect] == EffectAccess.ACCESS_MISSING -> MethodState.ACCESS_MISSING
            else -> MethodState.UNAVAILABLE
        }
    }
    MethodStatus(method, state)
}

/**
 * FR-033: show the background-stop message only when the VPN is down for a reason that
 * points at Android stopping the app, not when consent was revoked or another VPN took over
 * (those keep the usual limited-VPN message).
 */
fun showBackgroundStop(snapshot: ParentFacingSnapshot, reason: VpnUnavailableReason?): Boolean =
    snapshot.backgroundStopped && reason in BACKGROUND_STOP_REASONS

private val BACKGROUND_STOP_REASONS = setOf(
    VpnUnavailableReason.NOT_STARTED,
    VpnUnavailableReason.STOPPED,
    VpnUnavailableReason.FOREGROUND_NOT_ALLOWED,
)
