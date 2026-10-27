package com.kidsgarden.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The tunnel offers IPv6 only when the real network can use it (bug 2026-10-27: on IPv4-only
 * mobile data, browsers tried IPv6 through the tunnel and their connections were reset).
 */
class IpFamiliesTest {

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun ipv4OnlyMobileDataHasNoIpv6() {
        assertFalse(IpFamilies.usableIpv6(listOf(ip("10.131.199.89")), hasDefaultIpv6Route = false))
    }

    @Test
    fun linkLocalOrUniqueLocalOnlyIsNotUsable() {
        assertFalse(IpFamilies.usableIpv6(listOf(ip("fe80::1"), ip("10.0.0.2")), hasDefaultIpv6Route = true))
        assertFalse(IpFamilies.usableIpv6(listOf(ip("fd12:3456::1")), hasDefaultIpv6Route = true))
    }

    @Test
    fun globalAddressNeedsADefaultRoute() {
        assertFalse(IpFamilies.usableIpv6(listOf(ip("2a00:1450:4001::5")), hasDefaultIpv6Route = false))
        assertTrue(IpFamilies.usableIpv6(listOf(ip("10.0.0.2"), ip("2a00:1450:4001::5")), hasDefaultIpv6Route = true))
    }
}
