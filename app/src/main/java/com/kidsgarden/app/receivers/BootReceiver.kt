package com.kidsgarden.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kidsgarden.app.KidsGardenApplication
import com.kidsgarden.app.domain.DomainEvent

/**
 * Boot and app-update recovery (T082, FR-027). Both reaffirm DULL with no grant; nothing
 * about authorization is ever read from storage. Then, if the parent left VPN dulling on
 * and consent still holds, the VPN restarts through this permitted entry point so the
 * phone is dull again without the parent opening the app.
 *
 * Work is bounded: [AppContainer.recoverProtection] finishes within the broadcast budget.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> DomainEvent.RebootObserved
            Intent.ACTION_MY_PACKAGE_REPLACED -> DomainEvent.PackageReplaced
            else -> return
        }
        val container = (context.applicationContext as KidsGardenApplication).container
        container.sessionManager.dispatch(event)
        val pending = goAsync()
        container.recoverProtection { pending.finish() }
    }
}
