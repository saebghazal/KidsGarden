package com.kidsgarden.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import com.kidsgarden.app.R

/**
 * The single foreground notification (FR-029). It takes no session, mode, or time input,
 * so nothing about play, Soft Landing, or expiry can ever change what it shows. The channel
 * is silent: no sound, vibration, or badge.
 */
object StatusNotification {

    const val CHANNEL_ID = "kids_garden_status"
    const val NOTIFICATION_ID = 1

    fun channel(context: Context): NotificationChannel =
        NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

    fun build(context: Context, contentIntent: PendingIntent?): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel(context))
        // Neutral text only, safe on the lock screen.
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kids_garden)
            .setContentTitle(context.getString(R.string.notification_running))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
