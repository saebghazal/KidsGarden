package com.kidsgarden.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Tracks the best non-VPN network (Wi-Fi or cellular) and its resolvers, so DNS sent to
 * the virtual resolver follows network handover. Holds only the current network's resolver
 * addresses and whether it has IPv6 in memory; nothing about traffic.
 *
 * [hasIpv6] matters because the tunnel must only offer IPv6 when the real network has it:
 * otherwise apps (browsers first) try IPv6, the relay cannot reach the site, and the
 * connection is reset mid-handshake (bug 2026-10-27, IPv4-only mobile data). [onIpv6Changed]
 * lets the service rebuild the tunnel when that changes.
 */
internal class UnderlyingNetworkMonitor(
    context: Context,
    private val onNetworkChanged: (Network?) -> Unit,
    private val onIpv6Changed: (Boolean) -> Unit = {},
) : UpstreamDnsSource {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    var network: Network? = null
        private set

    @Volatile
    private var resolvers: List<InetAddress> = emptyList()

    /** The current real network has working IPv6 (a global address and a default IPv6 route). */
    @Volatile
    var hasIpv6: Boolean = false
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            this@UnderlyingNetworkMonitor.network = network
            val link = connectivity.getLinkProperties(network)
            resolvers = link?.dnsServers.orEmpty()
            updateIpv6(link)
            onNetworkChanged(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network != this@UnderlyingNetworkMonitor.network) return
            resolvers = linkProperties.dnsServers
            updateIpv6(linkProperties)
        }

        override fun onLost(network: Network) {
            if (network == this@UnderlyingNetworkMonitor.network) {
                this@UnderlyingNetworkMonitor.network = null
                resolvers = emptyList()
                onNetworkChanged(null)
            }
        }
    }

    private fun updateIpv6(link: LinkProperties?) {
        val now = link != null && hasUsableIpv6(link)
        if (now == hasIpv6) return
        hasIpv6 = now
        onIpv6Changed(now)
    }

    fun start() {
        // Before the TUN exists the active network is the real one; seed from it.
        connectivity.activeNetwork?.let { active ->
            val caps = connectivity.getNetworkCapabilities(active)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                network = active
                val link = connectivity.getLinkProperties(active)
                resolvers = link?.dnsServers.orEmpty()
                hasIpv6 = link != null && hasUsableIpv6(link)
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

    private fun hasUsableIpv6(link: LinkProperties): Boolean = IpFamilies.usableIpv6(
        addresses = link.linkAddresses.map { it.address },
        hasDefaultIpv6Route = link.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address },
    )
}

/** Pure check used by [UnderlyingNetworkMonitor]; unit-tested. */
internal object IpFamilies {
    /** True with a global (not link-local, not unique-local) IPv6 address and a default IPv6 route. */
    fun usableIpv6(addresses: List<InetAddress>, hasDefaultIpv6Route: Boolean): Boolean =
        hasDefaultIpv6Route && addresses.any { it is Inet6Address && isGlobal(it) }

    private fun isGlobal(address: Inet6Address): Boolean {
        if (address.isLinkLocalAddress || address.isLoopbackAddress || address.isAnyLocalAddress) return false
        // fc00::/7 unique-local addresses do not reach the internet.
        return (address.address[0].toInt() and 0xfe) != 0xfc
    }
}
