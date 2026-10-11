package com.kidsgarden.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runtime-registered receiver for SCREEN_OFF (T056). Android does not deliver this action
 * to manifest receivers. It only reports the observation; [DeviceEventCoordinator] turns it
 * into a domain event and the reducer decides what it means.
 */
class ScreenStateReceiver(private val onScreenOff: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
    }
}
