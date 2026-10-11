package com.kidsgarden.app.vpn

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback-only, authenticated SOCKS5 relay between the native tunnel and the network
 * (T041). Every egress socket is protected before use, so relayed traffic leaves through
 * the ordinary Wi-Fi/cellular network instead of looping back into the VPN.
 *
 * Privacy: requests carry numeric IPv4/IPv6 addresses only. Domain-name requests are
 * refused, never resolved. Payloads are copied as opaque bytes and never parsed. The only
 * address rewrite is the virtual resolver (198.18.0.2) → the underlying network's
 * resolver, by address and port alone. Nothing is printed, counted, or retained after a
 * connection closes.
 */
internal class LocalSocksRelay(
    private val scheduler: TrafficShapingScheduler,
    private val protector: SocketProtector,
    private val dns: UpstreamDnsSource,
    private val onProbe: () -> Unit,
) {
    private val random = SecureRandom()
    private val usernameBytes = randomHex(16).toByteArray(Charsets.US_ASCII)
    private val passwordBytes = randomHex(16).toByteArray(Charsets.US_ASCII)

    /** Random per start. Only datagrams carrying it can satisfy the forwarding probe. */
    val probeToken: ByteArray = ByteArray(16).also(random::nextBytes)

    val username: String get() = String(usernameBytes, Charsets.US_ASCII)
    val password: String get() = String(passwordBytes, Charsets.US_ASCII)

    private val server = ServerSocket()
    private val stopped = AtomicBoolean(false)
    private val open: MutableSet<Closeable> = Collections.newSetFromMap(ConcurrentHashMap())
    private val threadIds = AtomicInteger(0)

    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val virtualDns: InetAddress = numeric(TunnelAddresses.VIRTUAL_DNS_IPV4)
    private val probeAddress: InetAddress = numeric(TunnelAddresses.PROBE_IPV4)
    private val tunAddresses: Set<InetAddress> = setOf(numeric(TunnelAddresses.IPV4), numeric(TunnelAddresses.IPV6))

    val port: Int get() = server.localPort

    @Throws(IOException::class)
    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(loopback, 0), BACKLOG)
        spawn { acceptLoop() }
    }

    /** Idempotent. Closes the listener and every relayed socket. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        closeQuietly(server)
        for (closeable in open.toList()) closeQuietly(closeable)
        open.clear()
        probeToken.fill(0)
    }

    private fun acceptLoop() {
        while (!stopped.get()) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            if (!client.inetAddress.isLoopbackAddress) {
                closeQuietly(client)
                continue
            }
            track(client)
            spawn { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), HANDSHAKE_BUFFER))
            val output = client.getOutputStream()
            if (!negotiate(input, output)) return close(client)
            val request = readRequest(input, output) ?: return close(client)
            when (request.command) {
                CMD_CONNECT -> connect(client, input, output, request)
                CMD_UDP_ASSOCIATE -> associate(client, input, output)
                else -> {
                    reply(output, REP_COMMAND_NOT_SUPPORTED)
                    close(client)
                }
            }
        } catch (_: IOException) {
            close(client)
        }
    }

    /** Greeting plus username/password check (RFC 1928, RFC 1929). */
    private fun negotiate(input: DataInputStream, output: OutputStream): Boolean {
        if (input.readUnsignedByte() != SOCKS_VERSION) return false
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (methods.none { it == METHOD_USERNAME_PASSWORD }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NONE_ACCEPTABLE))
            return false
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_USERNAME_PASSWORD))
        if (input.readUnsignedByte() != AUTH_VERSION) return false
        val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val pass = ByteArray(input.readUnsignedByte()).also(input::readFully)
        // Evaluate both comparisons so timing does not reveal which one failed.
        val userOk = MessageDigest.isEqual(user, usernameBytes)
        val passOk = MessageDigest.isEqual(pass, passwordBytes)
        val ok = userOk and passOk
        output.write(byteArrayOf(AUTH_VERSION.toByte(), if (ok) 0 else 1))
        return ok
    }

    private class Request(val command: Int, val address: InetAddress, val port: Int)

    private fun readRequest(input: DataInputStream, output: OutputStream): Request? {
        if (input.readUnsignedByte() != SOCKS_VERSION) return null
        val command = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val address = when (input.readUnsignedByte()) {
            ATYP_IPV4 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully))
            ATYP_IPV6 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully))
            else -> {
                // Domain names are never resolved or examined.
                reply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                return null
            }
        }
        return Request(command, address, input.readUnsignedShort())
    }

    private fun connect(client: Socket, input: InputStream, output: OutputStream, request: Request) {
        val target = resolveTarget(request.address, request.port)
        if (target == null) {
            reply(output, REP_NOT_ALLOWED)
            return close(client)
        }
        val flow = scheduler.openFlow()
        if (flow == null) {
            reply(output, REP_GENERAL_FAILURE)
            return close(client)
        }
        val egress = Socket()
        track(egress)
        if (!protector.protect(egress)) {
            reply(output, REP_GENERAL_FAILURE)
            flow.close()
            return close(client, egress)
        }
        try {
            egress.tcpNoDelay = true
            egress.connect(target, CONNECT_TIMEOUT_MS)
        } catch (_: IOException) {
            reply(output, REP_CONNECTION_REFUSED)
            flow.close()
            return close(client, egress)
        }
        client.soTimeout = 0
        reply(output, REP_SUCCEEDED)

        val pending = AtomicInteger(2)
        val finish = { destination: Socket, failed: Boolean ->
            if (failed) close(client, egress) else shutdownOutputQuietly(destination)
            if (pending.decrementAndGet() == 0) {
                close(client, egress)
                flow.close()
            }
        }
        spawn { finish(egress, !pump(input, egress.getOutputStream(), Direction.UPSTREAM, flow)) }
        finish(client, !pump(egress.getInputStream(), output, Direction.DOWNSTREAM, flow))
    }

    /** Copies one direction until EOF. Returns false on error. Memory is one fixed buffer. */
    private fun pump(from: InputStream, to: OutputStream, direction: Direction, flow: TrafficShapingScheduler.FlowHandle): Boolean {
        val buffer = ByteArray(STREAM_CHUNK)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) return true
                if (n == 0) continue
                val readAt = System.nanoTime()
                if (!scheduler.reserveStream(n)) return false
                try {
                    if (!flow.send(direction, buffer, 0, n, readAt) { b, o, l -> to.write(b, o, l) }) return false
                } finally {
                    scheduler.release(n)
                }
            }
        } catch (_: IOException) {
            return false
        } finally {
            buffer.fill(0)
        }
    }

    private fun associate(control: Socket, input: InputStream, output: OutputStream) {
        val flow = scheduler.openFlow()
        if (flow == null) {
            reply(output, REP_GENERAL_FAILURE)
            return close(control)
        }
        val local = DatagramSocket(InetSocketAddress(loopback, 0))
        val egress = DatagramSocket()
        track(local)
        track(egress)
        if (!protector.protect(egress)) {
            reply(output, REP_GENERAL_FAILURE)
            flow.close()
            return close(control, local, egress)
        }
        reply(output, REP_SUCCEEDED, loopback, local.localPort)

        val association = UdpAssociation(local, egress, flow)
        spawn { association.upstream() }
        spawn { association.downstream() }
        // The association lives as long as its control connection (RFC 1928 §7).
        try {
            control.soTimeout = 0
            while (input.read() >= 0) Unit
        } catch (_: IOException) {
            // closed
        } finally {
            close(control, local, egress)
            association.clear()
            flow.close()
        }
    }

    private inner class UdpAssociation(
        private val local: DatagramSocket,
        private val egress: DatagramSocket,
        private val flow: TrafficShapingScheduler.FlowHandle,
    ) {
        @Volatile
        private var client: InetSocketAddress? = null

        /** Real resolver endpoint → the virtual one the system asked, for reply addressing. */
        private val dnsReplies = ConcurrentHashMap<InetSocketAddress, InetSocketAddress>()

        fun upstream() {
            val buffer = ByteArray(DATAGRAM_BUFFER)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (true) {
                    packet.setData(buffer, 0, buffer.size)
                    local.receive(packet)
                    val readAt = System.nanoTime()
                    val sender = packet.socketAddress as InetSocketAddress
                    if (!sender.address.isLoopbackAddress) continue
                    val known = client
                    if (known == null) client = sender else if (known != sender) continue
                    forwardUpstream(buffer, packet.length, readAt)
                }
            } catch (_: IOException) {
                // socket closed
            } finally {
                buffer.fill(0)
            }
        }

        private fun forwardUpstream(buffer: ByteArray, length: Int, readAt: Long) {
            // Header: RSV(2) FRAG(1) ATYP(1) ADDR PORT(2); fragments are not supported.
            if (length < 4 || buffer[2].toInt() != 0) return
            val addressLength = when (buffer[3].toInt()) {
                ATYP_IPV4 -> 4
                ATYP_IPV6 -> 16
                else -> return
            }
            val headerLength = 4 + addressLength + 2
            if (length < headerLength) return
            val address = InetAddress.getByAddress(buffer.copyOfRange(4, 4 + addressLength))
            val port = ((buffer[4 + addressLength].toInt() and 0xFF) shl 8) or (buffer[5 + addressLength].toInt() and 0xFF)
            val payloadLength = length - headerLength

            if (isProbe(address, port, buffer, headerLength, payloadLength)) {
                onProbe()
                return // never forwarded
            }
            val target = resolveTarget(address, port) ?: return
            if (target.address != address || target.port != port) {
                dnsReplies.putIfAbsent(target, InetSocketAddress(address, port))
                if (dnsReplies.size > MAX_DNS_MAPPINGS) dnsReplies.clear()
            }
            if (!scheduler.reserveDatagram(payloadLength)) return // bounded best-effort drop
            try {
                flow.send(Direction.UPSTREAM, buffer, headerLength, payloadLength, readAt) { b, o, l ->
                    egress.send(DatagramPacket(b, o, l, target))
                }
            } finally {
                scheduler.release(payloadLength)
            }
        }

        fun downstream() {
            val buffer = ByteArray(DATAGRAM_BUFFER)
            val packet = DatagramPacket(buffer, MAX_HEADER, buffer.size - MAX_HEADER)
            try {
                while (true) {
                    packet.setData(buffer, MAX_HEADER, buffer.size - MAX_HEADER)
                    egress.receive(packet)
                    val readAt = System.nanoTime()
                    val destination = client ?: continue
                    val from = packet.socketAddress as InetSocketAddress
                    val shownAs = dnsReplies[from] ?: from
                    val header = header(shownAs.address, shownAs.port)
                    val start = MAX_HEADER - header.size
                    System.arraycopy(header, 0, buffer, start, header.size)
                    val total = header.size + packet.length
                    if (!scheduler.reserveDatagram(total)) continue
                    try {
                        flow.send(Direction.DOWNSTREAM, buffer, start, total, readAt) { b, o, l ->
                            local.send(DatagramPacket(b, o, l, destination))
                        }
                    } finally {
                        scheduler.release(total)
                    }
                }
            } catch (_: IOException) {
                // socket closed
            } finally {
                buffer.fill(0)
            }
        }

        fun clear() {
            dnsReplies.clear()
            client = null
        }
    }

    private fun isProbe(address: InetAddress, port: Int, buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (address != probeAddress || port != TunnelAddresses.PROBE_PORT || length != probeToken.size) return false
        return MessageDigest.isEqual(buffer.copyOfRange(offset, offset + length), probeToken)
    }

    /**
     * Where a request really goes, or null if it must not be relayed. The virtual resolver
     * maps to the underlying network's resolver by address and port only.
     */
    private fun resolveTarget(address: InetAddress, port: Int): InetSocketAddress? {
        if (address == virtualDns) {
            if (port != DNS_PORT && port != DNS_TLS_PORT) return null
            val upstream = dns.current() ?: return null
            return InetSocketAddress(upstream, port)
        }
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address in tunAddresses) return null
        if (port == 0) return null
        return InetSocketAddress(address, port)
    }

    private fun header(address: InetAddress, port: Int): ByteArray {
        val raw = address.address
        val atyp = if (address is Inet6Address) ATYP_IPV6 else ATYP_IPV4
        val header = ByteArray(4 + raw.size + 2)
        header[3] = atyp.toByte()
        System.arraycopy(raw, 0, header, 4, raw.size)
        header[4 + raw.size] = (port shr 8).toByte()
        header[5 + raw.size] = port.toByte()
        return header
    }

    private fun reply(output: OutputStream, code: Int, address: InetAddress? = null, port: Int = 0) {
        val bound = address ?: InetAddress.getByAddress(ByteArray(4))
        val raw = bound.address
        val atyp = if (bound is Inet4Address) ATYP_IPV4 else ATYP_IPV6
        val message = ByteArray(4 + raw.size + 2)
        message[0] = SOCKS_VERSION.toByte()
        message[1] = code.toByte()
        message[3] = atyp.toByte()
        System.arraycopy(raw, 0, message, 4, raw.size)
        message[4 + raw.size] = (port shr 8).toByte()
        message[5 + raw.size] = port.toByte()
        try {
            output.write(message)
            output.flush()
        } catch (_: IOException) {
            // the peer is gone; nothing to report
        }
    }

    private fun track(closeable: Closeable) {
        open += closeable
        if (stopped.get()) closeQuietly(closeable)
    }

    private fun close(vararg closeables: Closeable) {
        for (closeable in closeables) {
            open -= closeable
            closeQuietly(closeable)
        }
    }

    private fun spawn(block: () -> Unit) {
        Thread(null, { block() }, "kg-relay-${threadIds.incrementAndGet()}", THREAD_STACK_BYTES).apply {
            isDaemon = true
            start()
        }
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BACKLOG = 128
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HANDSHAKE_BUFFER = 512

        /** 1024 flows × 2 directions × 8 KiB = the 16 MiB in-flight budget. */
        private const val STREAM_CHUNK = 8 * 1024
        private const val DATAGRAM_BUFFER = 65_535 + 32
        private const val MAX_HEADER = 4 + 16 + 2
        private const val MAX_DNS_MAPPINGS = 8
        private const val THREAD_STACK_BYTES = 128L * 1024

        private const val SOCKS_VERSION = 5
        private const val AUTH_VERSION = 1
        private const val METHOD_USERNAME_PASSWORD: Byte = 0x02
        private const val METHOD_NONE_ACCEPTABLE: Byte = 0xFF.toByte()
        private const val CMD_CONNECT = 1
        private const val CMD_UDP_ASSOCIATE = 3
        private const val ATYP_IPV4 = 1
        private const val ATYP_IPV6 = 4
        private const val REP_SUCCEEDED = 0
        private const val REP_GENERAL_FAILURE = 1
        private const val REP_NOT_ALLOWED = 2
        private const val REP_CONNECTION_REFUSED = 5
        private const val REP_COMMAND_NOT_SUPPORTED = 7
        private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 8
        private const val DNS_PORT = 53
        private const val DNS_TLS_PORT = 853

        /** Parses a numeric literal without any name lookup. */
        fun numeric(literal: String): InetAddress {
            require(literal.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }) {
                "numeric address required"
            }
            return InetAddress.getByName(literal)
        }

        private fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (_: IOException) {
                // already closed
            }
        }

        private fun shutdownOutputQuietly(socket: Socket) {
            try {
                socket.shutdownOutput()
            } catch (_: IOException) {
                // already closed
            }
        }
    }
}
