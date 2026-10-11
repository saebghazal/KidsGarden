package com.kidsgarden.app.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService

/**
 * Live permission observations (contracts/android-vpn.md "Adapter ownership"). Every answer
 * is read from the system at call time; unknown or revoked means unavailable.
 */
class PermissionStatusProvider(private val context: Context) {

    /** True when `VpnService.prepare` needs no consent dialog. */
    fun vpnConsentGranted(): Boolean = try {
        VpnService.prepare(context) == null
    } catch (_: IllegalStateException) {
        false
    }

    fun notificationsAllowed(): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val enabled = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
        return granted && enabled
    }
}
