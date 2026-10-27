package com.kidsgarden.app.vpn

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

    /** Whether the current tunnel offers IPv6; rebuilt when the real network gains or loses it. */
    private var tunnelIpv6 = false

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
        if (intent?.action == ACTION_RECONFIGURE) {
            // Only a running tunnel is rebuilt; otherwise the next start uses the new list.
            if (tun != null && startJob?.isActive != true) startJob = scope.launch { rebuildTunnel() }
            else if (tun == null && startJob?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            if (tun == null && startJob?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!controller.isStartAllowed()) {
            controller.onTunnelClosed(VpnUnavailableReason.DEACTIVATED)
            stopSelf(startId)
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

        val networkMonitor = UnderlyingNetworkMonitor(this, ::onUnderlyingNetworkChanged, ::onIpv6Changed).also { it.start() }
        monitor = networkMonitor

        val (descriptor, exclusions) = establishTunnel(networkMonitor)
        if (descriptor == null) return shutdown(VpnUnavailableReason.ESTABLISH_FAILED)
        tun = descriptor

        if (!controller.onTunnelEstablished(descriptor.fd, protector, networkMonitor, exclusions.status)) {
            shutdown(VpnUnavailableReason.ENGINE_FAILED)
        }
    }

    /**
     * The parent changed the apps that skip Kids Garden (FR-038). A new `establish()` replaces
     * the running interface with one that has the new exclusions; forwarding moves to it and the
     * old descriptor is closed. If the new interface cannot be made, the old one keeps running.
     */
    private suspend fun rebuildTunnel() {
        val networkMonitor = monitor ?: return
        val old = tun ?: return
        val (descriptor, exclusions) = establishTunnel(networkMonitor)
        if (descriptor == null) return
        tun = descriptor
        val ok = controller.onTunnelEstablished(descriptor.fd, protector, networkMonitor, exclusions.status)
        try {
            old.close()
        } catch (_: java.io.IOException) {
            // already closed
        }
        if (!ok) shutdown(VpnUnavailableReason.ENGINE_FAILED)
    }

    /** Builds and establishes the TUN with the current exclusions. Null descriptor on failure. */
    private fun establishTunnel(networkMonitor: UnderlyingNetworkMonitor): Pair<ParcelFileDescriptor?, ExclusionResult> {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TunnelAddresses.MTU)
            .addAddress(TunnelAddresses.IPV4, TunnelAddresses.IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
        // IPv6 only when the real network has it. Offering it on an IPv4-only network makes
        // apps (browsers first) try IPv6 that the relay cannot reach, and their connections
        // are reset (bug 2026-10-27). Without IPv6 configured, Android blocks IPv6 for VPN apps,
        // so they fail fast and use IPv4; nothing leaks around the VPN.
        tunnelIpv6 = networkMonitor.hasIpv6
        if (tunnelIpv6) {
            builder.addAddress(TunnelAddresses.IPV6, TunnelAddresses.IPV6_PREFIX).addRoute("::", 0)
        }
        builder.addDnsServer(TunnelAddresses.VIRTUAL_DNS_IPV4)
            // Meteredness follows the underlying network instead of always "metered".
            .setMetered(false)
            .setConfigureIntent(openAppIntent())
        // Resolved fresh before every establish; only exclusions, never an allow-list. The
        // parent's own list of apps that skip Kids Garden is added here (FR-038).
        val parentBypass = (application as KidsGardenApplication).container.settings.value?.bypassPackages.orEmpty()
        val exclusions = CommunicationAppExclusions(AndroidCommunicationPackageResolver(this))
            .apply(builder.asExclusionTarget(), parentBypass)
        networkMonitor.network?.let { builder.setUnderlyingNetworks(arrayOf(it)) }

        val descriptor = try {
            builder.establish()
        } catch (_: IllegalStateException) {
            null
        } catch (_: SecurityException) {
            null
        }
        return descriptor to exclusions
    }

    /** The real network gained or lost IPv6 (for example Wi-Fi to mobile data): rebuild the tunnel. */
    private fun onIpv6Changed(hasIpv6: Boolean) {
        if (tun != null && hasIpv6 != tunnelIpv6 && startJob?.isActive != true) startJob = scope.launch { rebuildTunnel() }
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
        startForeground(
            StatusNotification.NOTIFICATION_ID,
            StatusNotification.build(this, openAppIntent()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
        true
    } catch (_: IllegalStateException) {
        false // includes ForegroundServiceStartNotAllowedException
    } catch (_: SecurityException) {
        false
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
        const val ACTION_RECONFIGURE = "com.kidsgarden.app.vpn.RECONFIGURE"
    }
}
