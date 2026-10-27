package com.kidsgarden.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Android side of [VpnServiceLauncher]. */
class AndroidVpnServiceLauncher(private val context: Context) : VpnServiceLauncher {

    override fun consentGranted(): Boolean = VpnService.prepare(context) == null

    override fun startService() {
        val intent = Intent(context, KidsGardenVpnService::class.java).setAction(KidsGardenVpnService.ACTION_START)
        context.startForegroundService(intent)
    }

    override fun reconfigure() {
        val intent = Intent(context, KidsGardenVpnService::class.java).setAction(KidsGardenVpnService.ACTION_RECONFIGURE)
        try {
            context.startService(intent)
        } catch (_: IllegalStateException) {
            // Not allowed right now; the next VPN start applies the list.
        }
    }
}
