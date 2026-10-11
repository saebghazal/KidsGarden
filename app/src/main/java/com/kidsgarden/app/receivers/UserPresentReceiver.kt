package com.kidsgarden.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runtime-registered receiver for USER_PRESENT (device unlocked) (T056). Reports only. */
class UserPresentReceiver(private val onUserPresent: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) onUserPresent()
    }
}
