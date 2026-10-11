package com.kidsgarden.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.kidsgarden.app.KidsGardenApplication
import com.kidsgarden.app.MainActivity
import com.kidsgarden.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Socket

/**
 * Foreground VPN owner (T044, contracts/android-vpn.md "Lifecycle").
 *
 * - Starts only from [ACTION_START], sent by a visible parent screen. Any other start,
 *   including a system restart or always-on request, stops immediately: no restart loops.
 * - Foreground type systemExempted with the neutral "Kids Garden is running" notification;
 *   tapping it opens the app, which lands on the PIN screen.
 * - `establish()` returning null, revocation, engine failure, or stop all close the TUN so
 *   the device returns to ordinary routing, and report lost capability so grants end.
 */
class KidsGardenVpnService : VpnService() {

    private val controller: VpnDullingController
        get() = (application as KidsGardenApplication).container.vpnController

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startJob: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private var monitor: UnderlyingNetworkMonitor? = null

    private val handle = VpnServiceHandle { reason -> mainHandler.post { shutdown(reason) } }

    private val protector = object : SocketProtector {
        override fun protect(socket: Socket): Boolean = this@KidsGardenVpnService.protect(socket)
        override fun protect(socket: DatagramSocket): Boolean = this@KidsGardenVpnService.protect(socket)
    }

    override fun onCreate() {
        super.onCreate()
        controller.attachService(handle)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            if (tun == null && startJob?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            controller.onTunnelClosed(VpnUnavailableReason.FOREGROUND_NOT_ALLOWED)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (tun == null && startJob?.isActive != true) {
            startJob = scope.launch { establishAndForward() }
        }
        return START_NOT_STICKY
    }

    private suspend fun establishAndForward() {
        // Consent may have been revoked since the parent screen checked it.
        if (prepare(this) != null) return shutdown(VpnUnavailableReason.CONSENT_MISSING)

        val networkMonitor = UnderlyingNetworkMonitor(this, ::onUnderlyingNetworkChanged).also { it.start() }
        monitor = networkMonitor

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TunnelAddresses.MTU)
            .addAddress(TunnelAddresses.IPV4, TunnelAddresses.IPV4_PREFIX)
            .addAddress(TunnelAddresses.IPV6, TunnelAddresses.IPV6_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(TunnelAddresses.VIRTUAL_DNS_IPV4)
            // Meteredness follows the underlying network instead of always "metered".
            .setMetered(false)
            .setConfigureIntent(openAppIntent())
        CommunicationExclusions.apply(this, builder)
        networkMonitor.network?.let { builder.setUnderlyingNetworks(arrayOf(it)) }

        val descriptor = try {
            builder.establish()
        } catch (_: IllegalStateException) {
            null
        } catch (_: SecurityException) {
            null
        }
        if (descriptor == null) return shutdown(VpnUnavailableReason.ESTABLISH_FAILED)
        tun = descriptor

        if (!controller.onTunnelEstablished(descriptor.fd, protector, networkMonitor)) {
            shutdown(VpnUnavailableReason.ENGINE_FAILED)
        }
    }

    private fun onUnderlyingNetworkChanged(network: Network?) {
        // Existing flows end on handover as they would without a VPN; new ones use the new network.
        setUnderlyingNetworks(network?.let { arrayOf(it) })
    }

    /** Closes everything and returns the device to ordinary routing. Idempotent. */
    private fun shutdown(reason: VpnUnavailableReason) {
        startJob?.cancel()
        startJob = null
        controller.onTunnelClosed(reason)
        try {
            tun?.close()
        } catch (_: java.io.IOException) {
            // already closed
        }
        tun = null
        monitor?.stop()
        monitor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Another VPN, the user, or the system took the VPN away.
        shutdown(VpnUnavailableReason.REVOKED)
    }

    override fun onDestroy() {
        if (tun != null || startJob != null) shutdown(VpnUnavailableReason.STOPPED)
        controller.detachService(handle)
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        true
    } catch (_: IllegalStateException) {
        false // includes ForegroundServiceStartNotAllowedException
    } catch (_: SecurityException) {
        false
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        // Neutral text only: no mode, session, or time details, safe on the lock screen.
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kids_garden)
            .setContentTitle(getString(R.string.notification_running))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    /** Opens the app with no extras; the app itself routes to the PIN screen. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "com.kidsgarden.app.vpn.START"
        private const val CHANNEL_ID = "kids_garden_status"
        private const val NOTIFICATION_ID = 1
    }
}
