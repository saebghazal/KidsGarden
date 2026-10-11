package com.kidsgarden.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kidsgarden.app.KidsGardenApplication
import com.kidsgarden.app.domain.DomainEvent

/**
 * Boot and app-update notifications (FR-027). Both only reaffirm DULL with no grant; the
 * process start itself already did so. No work beyond dispatching one event.
 * T082 extends recovery through permitted entry points.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> DomainEvent.RebootObserved
            Intent.ACTION_MY_PACKAGE_REPLACED -> DomainEvent.PackageReplaced
            else -> return
        }
        (context.applicationContext as KidsGardenApplication).container.sessionManager.dispatch(event)
    }
}
