package com.kidsgarden.app.domain

import com.kidsgarden.app.domain.ReducerHarness.Companion.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recovery always ends in DULL with no restored authorization (T083). */
class RecoveryTest {

    private val restarts = listOf(DomainEvent.ProcessStarted(), DomainEvent.RebootObserved, DomainEvent.PackageReplaced)

    private fun busyStates(): List<ReducerHarness> = listOf(
        ReducerHarness().apply { startChild(30) },
        ReducerHarness().apply { startChild(10); advanceMinutes(9) }, // in the ramp
        ReducerHarness().apply { startChild(30); call(CallSourceState.ACTIVE) }, // paused
        ReducerHarness().apply { startParent() },
        ReducerHarness().apply { pin() }, // parent interface open, no grant
    )

    @Test
    fun everyRestartPathStartsAtDullWithNoGrantOrAccess() {
        for (event in restarts) {
            for (h in busyStates()) {
                val before = h.state.generation
                val result = h.send(event)
                assertEquals("$event", AuthorizationMode.DULL, h.state.mode)
                assertNull(h.state.childGrant)
                assertNull(h.state.parentAccess)
                assertNull(h.state.rampStage)
                assertTrue(h.state.generation > before)
                assertTrue(result.effects.any { it is Effect.CancelBoundary })
                assertTrue(result.effects.any { it is Effect.ClearParentUiAccess })
                assertTrue(result.effects.filterIsInstance<Effect.ApplyNetworkPolicy>().none { it.policy == NetworkPolicy.Normal })
            }
        }
    }

    @Test
    fun restartKeepsSettingsAndLiveCapabilities() {
        val h = ReducerHarness()
        h.startChild(30)
        h.send(DomainEvent.ProcessStarted())
        assertTrue(h.state.settings.credentialPresent)
        assertEquals(VpnCapability.RUNNING, h.state.vpnCapability)
        assertEquals(NetworkPolicy.Dull(DullIntensity.MEDIUM), h.lastPolicy())
    }

    @Test
    fun invalidSettingsResetToSafeDefaultsAndRequireEnrollment() {
        val h = ReducerHarness()
        h.startChild(30)
        h.send(DomainEvent.SettingsInvalid)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertEquals(DomainSettings(), h.state.settings)
        assertFalse(h.state.settings.credentialPresent)
        assertFalse(h.state.setupComplete)
        assertEquals(DisplayMode.SETUP_REQUIRED, h.state.displayMode)
        // Without a credential no new grant is possible, even with an old permission.
        val stalePermission = 2L
        h.send(DomainEvent.StartChildRequested(stalePermission, 10))
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun clockChangesInEitherDirectionAddNothing() {
        val h = ReducerHarness()
        h.startChild(20)
        h.advanceMinutes(4)
        for (shift in listOf(-24 * 60 * MINUTE, +7 * 24 * 60 * MINUTE, -1L, +1L)) {
            h.clock.shiftWallClock(shift)
            h.send(DomainEvent.UiOpened)
            assertEquals(16 * MINUTE, h.remaining())
        }
    }

    @Test
    fun elapsedClockGoingBackwardsRevokes() {
        val h = ReducerHarness()
        h.startChild(20)
        h.clock.elapsed -= 5 * MINUTE
        h.send(DomainEvent.UiOpened)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun vpnLossDuringAChildSessionIsDull() {
        val h = ReducerHarness()
        h.startChild(30)
        h.advanceMinutes(5)
        h.send(DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertEquals(DisplayMode.SETUP_REQUIRED, h.state.displayMode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun noSourceRestoresAuthorization() {
        val h = ReducerHarness()
        h.startChild(30)
        h.send(DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))
        val restoring = listOf(
            DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING),
            DomainEvent.UserPresent,
            DomainEvent.UiOpened,
            DomainEvent.InteractivityObserved(true),
            DomainEvent.SettingsChanged(h.state.settings),
            DomainEvent.CallAggregateChanged(h.observation(CallSourceState.INACTIVE)),
            DomainEvent.CallReconciled(h.observation(CallSourceState.ACTIVE)),
            DomainEvent.DeadlineObserved(h.state.generation),
            DomainEvent.SettingsWriteSucceeded,
            DomainEvent.PinVerified,
            DomainEvent.ProcessStarted(),
        )
        for (event in restoring) {
            h.send(event)
            assertEquals("after $event", AuthorizationMode.DULL, h.state.mode)
            assertNull("after $event", h.state.childGrant)
        }
    }

    @Test
    fun repeatedRestartsStayDull() {
        val h = ReducerHarness()
        h.startParent()
        repeat(5) { h.send(restarts[it % restarts.size]) }
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }
}
