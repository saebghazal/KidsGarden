package com.kidsgarden.app.vpn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression for the 2026-10-27 bug: every TCP connection through the tunnel was reset
 * because the relay protected its outgoing TCP socket before the OS socket existed, so
 * `VpnService.protect` failed. The socket must already be bound when it is protected.
 */
class RelayProtectTest {

    /** Records whether each TCP socket was bound when protected, then refuses it (no network needed). */
    private class RecordingProtector : SocketProtector {
        val boundWhenProtected = mutableListOf<Boolean>()
        val called = CountDownLatch(1)
        override fun protect(socket: Socket): Boolean {
            boundWhenProtected += socket.isBound && socket.localPort > 0
            called.countDown()
            return false
        }
        override fun protect(socket: DatagramSocket) = true
    }

    private val protector = RecordingProtector()
    private val relay = LocalSocksRelay(
        TrafficShapingScheduler(ThrottleProfile.UNSHAPED),
        protector,
        { null },
        {},
    ).also { it.start() }

    @After
    fun stop() = relay.stop()

    @Test
    fun tcpEgressIsBoundBeforeItIsProtected() {
        Socket(InetAddress.getLoopbackAddress(), relay.port).use { client ->
            val out = client.getOutputStream()
            val input = DataInputStream(client.getInputStream())
            // SOCKS5 greeting with username/password auth.
            out.write(byteArrayOf(5, 1, 2))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(2, input.readUnsignedByte())
            val user = relay.username.toByteArray()
            val pass = relay.password.toByteArray()
            out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
            assertEquals(1, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            // CONNECT to a TEST-NET address; the fake protector refuses it, so nothing is sent.
            out.write(byteArrayOf(5, 1, 0, 1, 192.toByte(), 0, 2, 10, 0, 80))
            assertTrue(protector.called.await(5, TimeUnit.SECONDS))
        }
        assertEquals(listOf(true), protector.boundWhenProtected)
    }
}
