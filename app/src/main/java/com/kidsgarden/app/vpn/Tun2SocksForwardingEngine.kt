package com.kidsgarden.app.vpn

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TUN → hev-socks5-tunnel (native, lwIP) → [LocalSocksRelay] → protected sockets (T041,
 * research R2). No remote gateway; the relay listens on loopback only and requires
 * per-start credentials.
 *
 * Running is reported only after a probe datagram sent through the TUN arrives at the
 * relay, which proves the whole path forwards. The probe targets a TEST-NET address and is
 * consumed by the relay, so no connection leaves the device.
 */
class Tun2SocksForwardingEngine : ForwardingEngine {

    private val lock = Any()
    private var relay: LocalSocksRelay? = null
    private var scheduler: TrafficShapingScheduler? = null
    private var tunnelThread: Thread? = null
    private var stopping = AtomicBoolean(true)

    override suspend fun start(
        configuration: EngineConfiguration,
        tunFd: Int,
        protector: SocketProtector,
        dns: UpstreamDnsSource,
        initial: ThrottleProfile,
        onFailure: (EngineFailure) -> Unit,
    ): EngineStartResult = withContext(Dispatchers.IO) {
        stop()
        if (!NativeTunnel.available) return@withContext EngineStartResult.Failed(EngineFailure.NATIVE_LIBRARY_MISSING)

        val newScheduler = TrafficShapingScheduler(
            initial,
            configuration.maxQueuedBytes,
            configuration.maxFlows,
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
        )
        val probeSeen = CompletableDeferred<Unit>()
        val newRelay = LocalSocksRelay(newScheduler, protector, dns) { probeSeen.complete(Unit) }
        try {
            newRelay.start()
        } catch (_: IOException) {
            newRelay.stop()
            return@withContext EngineStartResult.Failed(EngineFailure.RELAY_START_FAILED)
        }

        val exited = CompletableDeferred<Unit>()
        val stopFlag = AtomicBoolean(false)
        val failureReported = AtomicBoolean(false)
        val config = tunnelConfig(configuration, newRelay).toByteArray(Charsets.UTF_8)
        val thread = Thread(null, {
            NativeTunnel.run(config, tunFd)
            config.fill(0)
            exited.complete(Unit)
            if (!stopFlag.get() && failureReported.compareAndSet(false, true)) {
                onFailure(EngineFailure.TUNNEL_EXITED)
            }
        }, "kg-tunnel", 0)

        synchronized(lock) {
            relay = newRelay
            scheduler = newScheduler
            tunnelThread = thread
            stopping = stopFlag
        }
        thread.isDaemon = true
        thread.start()

        val verified = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            while (!probeSeen.isCompleted) {
                if (exited.isCompleted) return@withTimeoutOrNull false
                sendProbe(newRelay.probeToken)
                delay(PROBE_INTERVAL_MS)
            }
            true
        }
        if (verified != true) {
            // Failing here is reported through the result, not the callback.
            failureReported.set(true)
            val reason = if (exited.isCompleted) EngineFailure.TUNNEL_START_FAILED else EngineFailure.FORWARDING_NOT_VERIFIED
            stop()
            return@withContext EngineStartResult.Failed(reason)
        }
        EngineStartResult.Running
    }

    override fun apply(profile: ThrottleProfile, generation: Long, guard: PolicyGuard?): Boolean =
        synchronized(lock) { scheduler }?.apply(profile, generation, guard) ?: false

    override fun stop() {
        val (oldRelay, oldThread) = synchronized(lock) {
            stopping.set(true)
            val captured = relay to tunnelThread
            relay = null
            scheduler = null
            tunnelThread = null
            captured
        }
        if (oldThread != null && oldThread.isAlive) {
            // Quit can race the tunnel's own startup, so repeat it while waiting.
            var attempts = 0
            while (oldThread.isAlive && attempts < QUIT_ATTEMPTS) {
                NativeTunnel.quit()
                if (Thread.currentThread() !== oldThread) oldThread.join(QUIT_WAIT_MS)
                attempts++
            }
        }
        oldRelay?.stop()
    }

    /** Unprotected on purpose: it must travel through the TUN to reach the relay. */
    private fun sendProbe(token: ByteArray) {
        try {
            DatagramSocket().use { socket ->
                val target = InetAddress.getByAddress(PROBE_ADDRESS_BYTES)
                socket.send(DatagramPacket(token, token.size, target, TunnelAddresses.PROBE_PORT))
            }
        } catch (_: IOException) {
            // retried until the timeout
        }
    }

    private fun tunnelConfig(configuration: EngineConfiguration, relay: LocalSocksRelay): String = buildString {
        // Logging stays off: log-file is never set. Mapped DNS is never configured.
        appendLine("tunnel:")
        appendLine("  mtu: ${configuration.mtu}")
        appendLine("socks5:")
        appendLine("  port: ${relay.port}")
        appendLine("  address: '127.0.0.1'")
        appendLine("  udp: 'udp'")
        appendLine("  username: '${relay.username}'")
        appendLine("  password: '${relay.password}'")
        appendLine("misc:")
        appendLine("  max-session-count: ${configuration.maxFlows}")
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val PROBE_INTERVAL_MS = 250L
        const val QUIT_ATTEMPTS = 3
        const val QUIT_WAIT_MS = 500L
        val PROBE_ADDRESS_BYTES = byteArrayOf(192.toByte(), 0, 2, 1)
    }
}
