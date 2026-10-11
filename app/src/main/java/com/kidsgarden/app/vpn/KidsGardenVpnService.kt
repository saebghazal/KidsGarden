package com.kidsgarden.app.vpn

import android.content.Intent
import android.net.VpnService

/**
 * Skeleton declared by the manifest (T004). It establishes no tunnel and shapes nothing:
 * any start request stops immediately, so nothing can be mistaken for active protection.
 * T044 implements the real foreground VPN lifecycle.
 */
class KidsGardenVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
