package com.kidsgarden.app.vpn

import com.kidsgarden.app.data.SettingsDocument
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.NetworkPolicy
import com.kidsgarden.app.domain.VpnCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.Socket

/** VpnDullingController with a fake engine and launcher (T048). */
class FailSafePolicyTest {

    private class FakeEngine(var startResult: EngineStartResult = EngineStartResult.Running) : ForwardingEngine {
        val applied = mutableListOf<Pair<ThrottleProfile, Long>>()
        val guards = mutableListOf<PolicyGuard?>()
        var initial: ThrottleProfile? = null
        var stops = 0
        var onFailure: ((EngineFailure) -> Unit)? = null

        override suspend fun start(
            configuration: EngineConfiguration,
            tunFd: Int,
            protector: SocketProtector,
            dns: UpstreamDnsSource,
            initial: ThrottleProfile,
            onFailure: (EngineFailure) -> Unit,
        ): EngineStartResult {
            this.initial = initial
            this.onFailure = onFailure
            return startResult
        }

        override fun apply(profile: ThrottleProfile, generation: Long, guard: PolicyGuard?): Boolean {
            guards += guard
            return applied.add(profile to generation)
        }

        override fun stop() {
            stops++
        }

        val lastProfile: ThrottleProfile? get() = applied.lastOrNull()?.first
    }

    private class FakeLauncher(var consent: Boolean = true) : VpnServiceLauncher {
        var starts = 0
        var onStart: () -> Unit = {}
        override fun consentGranted(): Boolean = consent
        override fun startService() {
            starts++
            onStart()
        }
    }

    private object AlwaysProtect : SocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    private val noDns = UpstreamDnsSource { null }
    private val engine = FakeEngine()
    private val launcher = FakeLauncher()
    private var intensity = DullIntensity.MEDIUM
    private val controller = VpnDullingController(
        engine = engine,
        launcher = launcher,
        profiles = { SettingsDocument.DEFAULT_PROFILES },
        selectedIntensity = { intensity },
        startTimeoutMillis = 5_000,
    )

    private val mediumDull = ThrottleProfile.from(SettingsDocument.DEFAULT_PROFILES.getValue(DullIntensity.MEDIUM))
    private val strongDull = ThrottleProfile.from(SettingsDocument.DEFAULT_PROFILES.getValue(DullIntensity.STRONG))

    /** Simulates the service establishing the TUN as soon as it is started. */
    private fun TestScope.serviceEstablishesTunnel() {
        val scope: CoroutineScope = this
        launcher.onStart = { scope.launch { controller.onTunnelEstablished(42, AlwaysProtect, noDns) } }
    }

    @Test
    fun startWithoutConsentStaysUnavailableAndNeverStartsService() = runTest {
        launcher.consent = false
        val result = controller.start()
        assertEquals(VpnCapability.UNAVAILABLE, result.capability)
        assertEquals(VpnUnavailableReason.CONSENT_MISSING, result.reason)
        assertEquals(0, launcher.starts)
    }

    @Test
    fun runningOnlyAfterRealForwarding() = runTest {
        var seenWhenServiceStarted: VpnCapability? = null
        launcher.onStart = { seenWhenServiceStarted = controller.capability.value.capability }
        val pending = launch { controller.start() }
        testScheduler.runCurrent()
        assertEquals(VpnCapability.STARTING, seenWhenServiceStarted)
        assertEquals(VpnCapability.STARTING, controller.capability.value.capability)

        controller.onTunnelEstablished(42, AlwaysProtect, noDns)
        pending.join()
        assertEquals(VpnCapability.RUNNING, controller.capability.value.capability)
    }

    @Test
    fun engineThatCannotForwardIsUnavailableAndStopped() = runTest {
        engine.startResult = EngineStartResult.Failed(EngineFailure.FORWARDING_NOT_VERIFIED)
        serviceEstablishesTunnel()
        val result = controller.start()
        assertEquals(VpnCapability.UNAVAILABLE, result.capability)
        assertEquals(VpnUnavailableReason.ENGINE_FAILED, result.reason)
        assertTrue(engine.stops > 0)
    }

    @Test
    fun startTimesOutToUnavailable() = runTest {
        val result = controller.start() // the service never reports back
        assertEquals(VpnCapability.UNAVAILABLE, result.capability)
        assertEquals(VpnUnavailableReason.START_TIMEOUT, result.reason)
    }

    @Test
    fun engineStartsDullBeforeAnyPolicyArrives() = runTest {
        serviceEstablishesTunnel()
        controller.start()
        assertEquals(mediumDull, engine.initial)
        assertEquals(mediumDull, engine.lastProfile)
    }

    @Test
    fun staleGenerationsAreIgnored() = runTest {
        serviceEstablishesTunnel()
        controller.start()
        controller.apply(NetworkPolicy.Dull(DullIntensity.STRONG), generation = 5)
        controller.apply(NetworkPolicy.Normal, generation = 4)
        assertEquals(strongDull, engine.lastProfile)
        assertEquals(5L, engine.applied.last().second)
    }

    @Test
    fun normalIsAppliedOnlyWhenTheDomainAsksForIt() = runTest {
        serviceEstablishesTunnel()
        controller.start()
        assertEquals(mediumDull, engine.lastProfile)
        controller.apply(NetworkPolicy.Normal, generation = 1)
        assertEquals(ThrottleProfile.UNSHAPED, engine.lastProfile)
    }

    @Test
    fun unavailablePolicyNeverUnshapesARunningEngine() = runTest {
        serviceEstablishesTunnel()
        controller.start()
        intensity = DullIntensity.STRONG
        controller.apply(NetworkPolicy.Unavailable("vpn-unavailable"), generation = 1)
        assertEquals(strongDull, engine.lastProfile)
    }

    @Test
    fun policiesBeforeForwardingAreHeldAndAppliedAtStart() = runTest {
        controller.apply(NetworkPolicy.Dull(DullIntensity.STRONG), generation = 2)
        assertTrue(engine.applied.isEmpty())
        serviceEstablishesTunnel()
        controller.start()
        assertEquals(strongDull, engine.initial)
        assertEquals(strongDull, engine.lastProfile)
    }

    @Test
    fun engineFailureRevokesCapabilityAndClosesTheTunnel() = runTest {
        val shutdowns = mutableListOf<VpnUnavailableReason>()
        controller.attachService { reason -> shutdowns += reason }
        serviceEstablishesTunnel()
        controller.start()

        engine.onFailure!!.invoke(EngineFailure.TUNNEL_EXITED)

        assertEquals(VpnCapability.UNAVAILABLE, controller.capability.value.capability)
        assertEquals(listOf(VpnUnavailableReason.ENGINE_FAILED), shutdowns)
    }

    @Test
    fun stopFailsOpenByStoppingTheEngine() = runTest {
        serviceEstablishesTunnel()
        controller.start()
        controller.stop(VpnUnavailableReason.STOPPED)
        assertEquals(VpnCapability.UNAVAILABLE, controller.capability.value.capability)
        assertTrue(engine.stops > 0)
        // After stop, new policies reach nothing.
        val before = engine.applied.size
        controller.apply(NetworkPolicy.Normal, generation = 9)
        assertEquals(before, engine.applied.size)
    }

    @Test
    fun relaxedPolicyCarriesItsDeadlineAndDullFallback() = runTest {
        serviceEstablishesTunnel()
        controller.start()
        controller.apply(NetworkPolicy.Normal, generation = 3, validUntilElapsedMillis = 600_000L)
        assertEquals(PolicyGuard(600_000L, mediumDull), engine.guards.last())
        controller.apply(NetworkPolicy.Dull(DullIntensity.MEDIUM), generation = 4)
        assertEquals(null, engine.guards.last())
    }

    @Test
    fun revocationReportsLostCapability() = runTest {
        serviceEstablishesTunnel()
        controller.start()
        controller.onTunnelClosed(VpnUnavailableReason.REVOKED)
        assertEquals(
            VpnCapabilitySnapshot(VpnCapability.UNAVAILABLE, VpnUnavailableReason.REVOKED),
            controller.capability.value,
        )
    }
}
