package com.kidsgarden.app.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Packages that bypass the VPN entirely (spec FR-021, research R10): the default and system
 * dialers, the default SMS/RCS app, and WhatsApp. Resolved fresh at every establish and
 * never stored. Each package is handled on its own: one that is missing or unresolvable
 * is skipped without affecting the others or dulling of everything else.
 */
internal object CommunicationExclusions {

    const val WHATSAPP = "com.whatsapp"

    fun resolve(context: Context): Set<String> {
        val telecom = context.getSystemService(TelecomManager::class.java)
        return buildSet {
            telecom?.defaultDialerPackage?.let(::add)
            telecom?.systemDialerPackage?.let(::add)
            Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
            add(WHATSAPP)
            remove(context.packageName)
        }
    }

    /** Returns how many exclusions were applied. */
    fun apply(context: Context, builder: VpnService.Builder): Int {
        var applied = 0
        for (packageName in resolve(context)) {
            try {
                builder.addDisallowedApplication(packageName)
                applied++
            } catch (_: PackageManager.NameNotFoundException) {
                // Not installed or not visible: skip this one only.
            }
        }
        return applied
    }
}
