package com.kidsgarden.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.UserManager

/** Android side of [CompatibilityProbe]: on-device state only. */
class AndroidCompatibilityProbe(context: Context) : CompatibilityProbe {

    private val users = context.getSystemService(UserManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun vpnBlockedByPolicy(): Boolean = users?.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN) == true

    /** With our VPN stopped, a VPN carrying this app's default network belongs to another app. */
    override fun otherVpnActive(): Boolean {
        val manager = connectivity ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}
