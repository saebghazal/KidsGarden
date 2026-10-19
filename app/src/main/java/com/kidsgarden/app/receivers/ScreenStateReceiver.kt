package com.kidsgarden.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runtime-registered receiver for SCREEN_OFF and SCREEN_ON (T056, FR-035). Android does
 * not deliver these actions to manifest receivers. It only reports the observation;
 * [DeviceEventCoordinator] turns it into a domain event and the reducer decides what it means.
 */
class ScreenStateReceiver(
    private val onScreenOff: () -> Unit,
    private val onScreenOn: () -> Unit = {},
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> onScreenOff()
            Intent.ACTION_SCREEN_ON -> onScreenOn()
        }
    }
}
