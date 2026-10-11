package com.kidsgarden.app.receivers

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.domain.SessionManager
import com.kidsgarden.app.domain.VpnCapability
import com.kidsgarden.app.vpn.VpnUnavailableReason

/**
 * Translates screen and lock observations into domain events (T056,
 * contracts/android-vpn.md "DeviceEventCoordinator"). It makes no policy decisions.
 *
 * Broadcasts alone are not trusted to arrive, so [sample] reads `isInteractive` and
 * `isDeviceLocked` directly. It runs at process start, on every activity resume, and just
 * before any request that would relax the network policy.
 */
class DeviceEventCoordinator(
    context: Context,
    private val sessionManager: SessionManager,
) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)

    private val screenReceiver = ScreenStateReceiver {
        sessionManager.dispatch(DomainEvent.ScreenOff)
    }
    private val presentReceiver = UserPresentReceiver {
        sessionManager.dispatch(DomainEvent.UserPresent)
    }

    private var registered = false

    fun start() {
        if (registered) return
        // System broadcasts still reach not-exported receivers.
        appContext.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        appContext.registerReceiver(presentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
        registered = true
        sample()
    }

    /**
     * A running VPN stopped: revoked by the user or system, replaced by another VPN app,
     * stopped by the user, or failed. Reported directly so grants end even if capability
     * returns before a flow collector runs. The reducer decides what it means.
     */
    fun vpnLost(@Suppress("UNUSED_PARAMETER") reason: VpnUnavailableReason) {
        sessionManager.dispatch(DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))
    }

    /** Reports the current screen and lock state. Unknown counts as locked. */
    fun sample() {
        val interactive = power?.isInteractive == true
        val locked = keyguard?.isDeviceLocked != false
        val event = when {
            !interactive -> DomainEvent.ScreenOff
            locked -> DomainEvent.DeviceLocked
            else -> DomainEvent.InteractivityObserved(interactiveAndUnlocked = true)
        }
        sessionManager.dispatch(event)
    }
}
