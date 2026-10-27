package com.kidsgarden.app.vpn

import com.kidsgarden.app.data.SettingsDocument
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.VpnCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramSocket
import java.net.Socket

/** The local phone compatibility check with fakes (T118, FR-032). */
class CompatibilityCheckTest {

    private class FakeEngine(var result: EngineStartResult = EngineStartResult.Running) : ForwardingEngine {
        var starts = 0
        override suspend fun start(
            configuration: EngineConfiguration,
            tunFd: Int,
            protector: SocketProtector,
            dns: UpstreamDnsSource,
            initial: ThrottleProfile,
            onFailure: (EngineFailure) -> Unit,
        ): EngineStartResult {
            starts++
            return result
        }
        override fun apply(profile: ThrottleProfile, generation: Long, guard: PolicyGuard?) = true
        override fun stop() = Unit
    }

    private class FakeLauncher(var consent: Boolean = true) : VpnServiceLauncher {
        var starts = 0
        var onStart: () -> Unit = {}
        override fun consentGranted() = consent
        override fun startService() {
            starts++
            onStart()
        }
    }

    private class FakeProbe(var blocked: Boolean = false, var otherVpn: Boolean = false) : CompatibilityProbe {
        override fun vpnBlockedByPolicy() = blocked
        override fun otherVpnActive() = otherVpn
    }

    /** Counts any socket the check would protect; the check must never open one. */
    private class CountingProtector : SocketProtector {
        var protected = 0
        override fun protect(socket: Socket): Boolean {
            protected++
            return true
        }
        override fun protect(socket: DatagramSocket): Boolean {
            protected++
            return true
        }
    }

    private val engine = FakeEngine()
    private val launcher = FakeLauncher()
    private val probe = FakeProbe()
    private val protector = CountingProtector()
    private val controller = VpnDullingController(
        engine = engine,
        launcher = launcher,
        profiles = { SettingsDocument.DEFAULT_PROFILES },
        selectedIntensity = { DullIntensity.MEDIUM },
        startTimeoutMillis = 5_000,
        probe = probe,
    )

    /** The service establishes the TUN as soon as it is started. */
    private fun TestScope.serviceEstablishes() {
        val scope: CoroutineScope = this
        launcher.onStart = { scope.launch { controller.onTunnelEstablished(42, protector, { null }) } }
    }

    private fun failed(reason: CompatibilityFailure) = CompatibilityResult.Failed(reason)

    @Test
    fun passWhenTrafficFlowsLocally() = runTest {
        serviceEstablishes()
        assertEquals(CompatibilityResult.Pass, controller.checkCompatibility())
        assertEquals(VpnCapability.RUNNING, controller.capability.value.capability)
        assertEquals(0, protector.protected)
    }

    @Test
    fun noConsentIsReportedFirst() = runTest {
        launcher.consent = false
        probe.blocked = true
        assertEquals(failed(CompatibilityFailure.NO_CONSENT), controller.checkCompatibility())
        assertEquals(0, launcher.starts)
    }

    @Test
    fun policyBlockIsReported() = runTest {
        probe.blocked = true
        assertEquals(failed(CompatibilityFailure.POLICY_BLOCKED), controller.checkCompatibility())
        assertEquals(0, launcher.starts)
    }

    @Test
    fun anotherVpnIsReportedWithoutStarting() = runTest {
        probe.otherVpn = true
        assertEquals(failed(CompatibilityFailure.OTHER_VPN), controller.checkCompatibility())
        assertEquals(0, launcher.starts)
    }

    @Test
    fun foregroundServiceRefusalIsReported() = runTest {
        launcher.onStart = { throw IllegalStateException("not allowed") }
        assertEquals(failed(CompatibilityFailure.FGS_NOT_ALLOWED), controller.checkCompatibility())
    }

    @Test
    fun establishFailureMeansPolicyBlocked() = runTest {
        val scope: CoroutineScope = this
        launcher.onStart = { scope.launch { controller.onTunnelClosed(VpnUnavailableReason.ESTABLISH_FAILED) } }
        assertEquals(failed(CompatibilityFailure.POLICY_BLOCKED), controller.checkCompatibility())
    }

    @Test
    fun noForwardingMeansNoTraffic() = runTest {
        engine.result = EngineStartResult.Failed(EngineFailure.FORWARDING_NOT_VERIFIED)
        serviceEstablishes()
        assertEquals(failed(CompatibilityFailure.NO_TRAFFIC), controller.checkCompatibility())
        assertEquals(0, protector.protected)
    }

    @Test
    fun startTimeoutMeansNoTraffic() = runTest {
        // The service never reports back.
        assertEquals(failed(CompatibilityFailure.NO_TRAFFIC), controller.checkCompatibility())
    }

    @Test
    fun alreadyRunningPassesWithoutRestart() = runTest {
        serviceEstablishes()
        controller.checkCompatibility()
        val starts = engine.starts
        probe.otherVpn = true // our own VPN is the active one
        assertEquals(CompatibilityResult.Pass, controller.checkCompatibility())
        assertEquals(starts, engine.starts)
    }
}
