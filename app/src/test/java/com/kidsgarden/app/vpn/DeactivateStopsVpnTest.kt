package com.kidsgarden.app.vpn

import com.kidsgarden.app.data.SettingsDocument
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.VpnCapability
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramSocket
import java.net.Socket

/**
 * Turning Kids Garden off fully stops the VPN (FR-031, clarification 2026-10-27): the service
 * is shut down (tunnel closed, foreground notification removed), forwarding stops, nothing can
 * start it again while off, and a changed bypass list does not wake it.
 */
class DeactivateStopsVpnTest {

    private class Engine : ForwardingEngine {
        var running = false
        override suspend fun start(
            configuration: EngineConfiguration,
            tunFd: Int,
            protector: SocketProtector,
            dns: UpstreamDnsSource,
            initial: ThrottleProfile,
            onFailure: (EngineFailure) -> Unit,
        ): EngineStartResult {
            running = true
            return EngineStartResult.Running
        }
        override fun apply(profile: ThrottleProfile, generation: Long, guard: PolicyGuard?) = true
        override fun stop() {
            running = false
        }
    }

    private class Launcher : VpnServiceLauncher {
        var starts = 0
        var reconfigures = 0
        override fun consentGranted() = true
        override fun startService() {
            starts++
        }
        override fun reconfigure() {
            reconfigures++
        }
    }

    private object Protect : SocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    @Test
    fun deactivateClosesTheServiceAndNothingRestartsIt() = runTest {
        val engine = Engine()
        val launcher = Launcher()
        var active = true
        val controller = VpnDullingController(
            engine = engine,
            launcher = launcher,
            profiles = { SettingsDocument.DEFAULT_PROFILES },
            selectedIntensity = { DullIntensity.MEDIUM },
            startAllowed = { active },
        )
        val shutdowns = mutableListOf<VpnUnavailableReason>()
        // The real service closes the TUN and stops its foreground notification, then reports back.
        controller.attachService { reason ->
            shutdowns += reason
            controller.onTunnelClosed(reason)
        }
        controller.onTunnelEstablished(42, Protect, { null })
        assertEquals(VpnCapability.RUNNING, controller.capability.value.capability)

        // Deactivate: StopVpn effect.
        active = false
        controller.stop(VpnUnavailableReason.DEACTIVATED)

        assertEquals(listOf(VpnUnavailableReason.DEACTIVATED), shutdowns)
        assertEquals(false, engine.running)
        assertEquals(VpnCapability.UNAVAILABLE, controller.capability.value.capability)
        assertEquals(VpnUnavailableReason.DEACTIVATED, controller.capability.value.reason)

        // Boot recovery, setup screens or a bypass-list change cannot start it while off.
        controller.requestStart()
        controller.reconfigure()
        assertEquals(0, launcher.starts)
        assertEquals(0, launcher.reconfigures)
        assertEquals(VpnUnavailableReason.DEACTIVATED, controller.capability.value.reason)
    }

    @Test
    fun aRunningVpnRebuildsForANewBypassList() = runTest {
        val launcher = Launcher()
        val controller = VpnDullingController(
            engine = Engine(),
            launcher = launcher,
            profiles = { SettingsDocument.DEFAULT_PROFILES },
            selectedIntensity = { DullIntensity.MEDIUM },
        )
        controller.reconfigure()
        assertEquals(0, launcher.reconfigures) // not running: the next start uses the list

        controller.onTunnelEstablished(42, Protect, { null })
        controller.reconfigure()
        assertEquals(1, launcher.reconfigures)
        assertEquals(VpnCapability.RUNNING, controller.capability.value.capability)
    }
}
