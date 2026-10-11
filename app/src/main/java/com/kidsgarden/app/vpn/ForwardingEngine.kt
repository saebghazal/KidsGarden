package com.kidsgarden.app.vpn

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Coarse, allowlisted failure reasons. Never carries payloads, addresses, URLs, or
 * exception text (Constitution III).
 */
enum class EngineFailure {
    NATIVE_LIBRARY_MISSING,
    RELAY_START_FAILED,
    TUNNEL_START_FAILED,
    TUNNEL_EXITED,
    FORWARDING_NOT_VERIFIED,
}

sealed interface EngineStartResult {
    /** TUN traffic was observed reaching the relay. */
    data object Running : EngineStartResult
    data class Failed(val reason: EngineFailure) : EngineStartResult
}

/** Exempts egress sockets from the VPN (`VpnService.protect`). False means do not use it. */
interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean
}

/** The underlying network's current resolver, looked up when a DNS packet is forwarded. */
fun interface UpstreamDnsSource {
    fun current(): InetAddress?
}

/** Fixed numeric configuration. Nothing here is persisted. */
data class EngineConfiguration(
    val mtu: Int = TunnelAddresses.MTU,
    val maxFlows: Int = TrafficShapingScheduler.DEFAULT_MAX_FLOWS,
    val maxQueuedBytes: Int = TrafficShapingScheduler.DEFAULT_MAX_QUEUED_BYTES,
)

/**
 * Local forwarding behind the VPN (T041, contracts/android-vpn.md "Forwarding-engine
 * contract"). Implementations forward opaque routed bytes; they never parse payloads,
 * DNS names, SNI, or URLs, never print, and never retain destinations.
 */
interface ForwardingEngine {

    /**
     * Starts forwarding from [tunFd] and returns [EngineStartResult.Running] only once
     * traffic actually forwards. [initial] is enforced from the first packet.
     * [onFailure] is called at most once if forwarding later stops by itself.
     */
    suspend fun start(
        configuration: EngineConfiguration,
        tunFd: Int,
        protector: SocketProtector,
        dns: UpstreamDnsSource,
        initial: ThrottleProfile,
        onFailure: (EngineFailure) -> Unit,
    ): EngineStartResult

    /** Applies [profile] unless [generation] is stale. */
    fun apply(profile: ThrottleProfile, generation: Long): Boolean

    /** Idempotent. Closes sockets and clears buffers and flow maps. */
    fun stop()
}

/** Addresses inside the TUN. 198.18.0.0/15 is reserved for benchmarking (RFC 2544). */
object TunnelAddresses {
    const val MTU = 8500
    const val IPV4 = "198.18.0.1"
    const val IPV4_PREFIX = 32

    /** Virtual resolver handed to the system; the relay redirects it to the real one. */
    const val VIRTUAL_DNS_IPV4 = "198.18.0.2"
    const val IPV6 = "fd00:6b67::1"
    const val IPV6_PREFIX = 128

    /** TEST-NET-1 (RFC 5737) discard target used only for the local forwarding probe. */
    const val PROBE_IPV4 = "192.0.2.1"
    const val PROBE_PORT = 9
}
