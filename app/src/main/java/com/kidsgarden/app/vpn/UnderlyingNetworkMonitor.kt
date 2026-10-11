package com.kidsgarden.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

/**
 * Tracks the best non-VPN network (Wi-Fi or cellular) and its resolvers, so DNS sent to
 * the virtual resolver follows network handover. Holds only the current network's resolver
 * addresses in memory; nothing about traffic.
 */
internal class UnderlyingNetworkMonitor(
    context: Context,
    private val onNetworkChanged: (Network?) -> Unit,
) : UpstreamDnsSource {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    var network: Network? = null
        private set

    @Volatile
    private var resolvers: List<InetAddress> = emptyList()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            this@UnderlyingNetworkMonitor.network = network
            resolvers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
            onNetworkChanged(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == this@UnderlyingNetworkMonitor.network) resolvers = linkProperties.dnsServers
        }

        override fun onLost(network: Network) {
            if (network == this@UnderlyingNetworkMonitor.network) {
                this@UnderlyingNetworkMonitor.network = null
                resolvers = emptyList()
                onNetworkChanged(null)
            }
        }
    }

    fun start() {
        // Before the TUN exists the active network is the real one; seed from it.
        connectivity.activeNetwork?.let { active ->
            val caps = connectivity.getNetworkCapabilities(active)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                network = active
                resolvers = connectivity.getLinkProperties(active)?.dnsServers.orEmpty()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerBestMatchingNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // never registered
        }
        network = null
        resolvers = emptyList()
    }

    override fun current(): InetAddress? = resolvers.firstOrNull()
}
