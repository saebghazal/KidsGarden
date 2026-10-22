package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Active/Deactivated switch (T101, FR-031, contracts/state-machine.md). */
class ActivationTest {

    private val h = ReducerHarness()

    /** Confirmed Deactivate from the parent interface, then a successful save. */
    private fun deactivate() {
        if (h.state.parentAccess == null) h.pin()
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        h.send(DomainEvent.ActiveWriteSucceeded(active = false))
    }

    private fun assertDeactivatedClean(result: ReduceResult) {
        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
        assertEquals(DisplayMode.DEACTIVATED, h.state.displayMode)
        assertNull(h.state.childGrant)
        assertNull(h.state.rampStage)
        assertNull(h.state.parentAccess?.grantPermission)
        assertTrue(result.effects.any { it is Effect.StopVpn })
        assertTrue(result.effects.any { it is Effect.CancelBoundary })
        assertFalse(result.effects.any { it is Effect.ApplyNetworkPolicy && it.policy == NetworkPolicy.Normal })
    }

    @Test
    fun deactivateRequestOnlySavesUntilTheWriteSucceeds() {
        h.pin()
        val request = h.send(DomainEvent.DeactivateRequested(confirmed = true))

        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertEquals(listOf(Effect.PersistActive(false, h.state.generation)), request.effects)
    }

    @Test
    fun deactivateFromDull() {
        h.pin()
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        assertDeactivatedClean(h.send(DomainEvent.ActiveWriteSucceeded(active = false)))
    }

    @Test
    fun deactivateFromParentMode() {
        h.startParent()
        assertEquals(AuthorizationMode.PARENT_MODE, h.state.mode)
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        assertDeactivatedClean(h.send(DomainEvent.ActiveWriteSucceeded(active = false)))
    }

    @Test
    fun deactivateFromChildSession() {
        h.startChild(30)
        h.advanceMinutes(5)
        h.pin()
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        assertDeactivatedClean(h.send(DomainEvent.ActiveWriteSucceeded(active = false)))
    }

    @Test
    fun deactivateFromSoftLanding() {
        h.startChild(10)
        h.advanceMinutes(9)
        h.pin()
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        assertDeactivatedClean(h.send(DomainEvent.ActiveWriteSucceeded(active = false)))
    }

    @Test
    fun deactivateDuringCallPauseEndsThePause() {
        h.startChild(30)
        h.call(CallSourceState.ACTIVE)
        assertTrue(h.state.childGrant!!.isPaused)
        h.pin()
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        assertDeactivatedClean(h.send(DomainEvent.ActiveWriteSucceeded(active = false)))

        h.call(CallSourceState.INACTIVE)
        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun deactivateNeedsConfirmationAndParentAccess() {
        h.pin()
        assertTrue(h.send(DomainEvent.DeactivateRequested(confirmed = false)).effects.isEmpty())
        h.send(DomainEvent.ParentUiLeft)
        assertTrue(h.send(DomainEvent.DeactivateRequested(confirmed = true)).effects.isEmpty())
        assertNull(h.state.pendingActive)
    }

    @Test
    fun failedDeactivateWriteLeavesStateUnchanged() {
        h.startChild(30)
        val grant = h.state.childGrant
        h.pin()
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        h.send(DomainEvent.ActiveWriteFailed(active = false))

        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
        assertEquals(grant, h.state.childGrant)
        assertNull(h.state.pendingActive)
    }

    @Test
    fun everyOtherEventIsIgnoredWhileDeactivated() {
        deactivate()
        val generation = h.state.generation
        val ignored = listOf(
            DomainEvent.UserPresent,
            DomainEvent.DeviceLocked,
            DomainEvent.ScreenOn,
            DomainEvent.ScreenOff,
            DomainEvent.InteractivityObserved(interactiveAndUnlocked = true),
            DomainEvent.UiOpened,
            DomainEvent.ManualParentExit,
            DomainEvent.VpnStopRequested,
            DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING),
            DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE),
            DomainEvent.CallCapabilityChanged(CallDetectionCapability.PERMISSION_MISSING),
            DomainEvent.CallAggregateChanged(h.observation(CallSourceState.ACTIVE)),
            DomainEvent.CallReconciled(h.observation(CallSourceState.INACTIVE)),
            DomainEvent.DeadlineObserved(generation),
            DomainEvent.RebootObserved,
            DomainEvent.PackageReplaced,
            DomainEvent.SettingsWriteSucceeded,
            DomainEvent.SettingsWriteFailed,
            DomainEvent.DeactivateRequested(confirmed = true),
            DomainEvent.ActiveWriteSucceeded(active = true),
        )
        for (event in ignored) {
            val result = h.send(event)
            assertEquals("$event", AuthorizationMode.DEACTIVATED, h.state.mode)
            assertFalse("$event", result.effects.any { it is Effect.ApplyNetworkPolicy || it is Effect.StartVpn })
        }
    }

    @Test
    fun grantsAreRefusedWhileDeactivated() {
        deactivate()
        val permission = h.pin()
        h.send(DomainEvent.StartParentRequested(permission))
        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
        h.send(DomainEvent.StartChildRequested(permission, 30))
        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun activateFromParentInterfaceReturnsToDullWithNoGrant() {
        deactivate()
        // The parent is still in the parent interface: no second PIN entry is needed.
        val request = h.send(DomainEvent.ActivateRequested)
        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
        assertTrue(request.effects.single() is Effect.PersistActive)

        val result = h.send(DomainEvent.ActiveWriteSucceeded(active = true))

        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertEquals(DisplayMode.DULL, h.state.displayMode)
        assertNull(h.state.childGrant)
        assertTrue(h.state.parentAccess != null)
        // Regression (2026-10-19): Activate must not close the parent interface.
        assertFalse(result.effects.any { it is Effect.ClearParentUiAccess })
        assertTrue(result.effects.any { it is Effect.StartVpn })
        assertFalse(result.effects.any { it is Effect.ApplyNetworkPolicy && it.policy == NetworkPolicy.Normal })
        assertEquals(NetworkPolicy.Dull(DullIntensity.MEDIUM), h.lastPolicy())
    }

    @Test
    fun activateWithoutWorkingVpnShowsSetupRequired() {
        deactivate()
        h.send(DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))
        h.send(DomainEvent.ActivateRequested)
        h.send(DomainEvent.ActiveWriteSucceeded(active = true))

        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertEquals(DisplayMode.SETUP_REQUIRED, h.state.displayMode)
        assertTrue(h.lastPolicy() is NetworkPolicy.Unavailable)
    }

    @Test
    fun activateNeedsParentAccess() {
        deactivate()
        h.send(DomainEvent.ParentUiLeft)
        assertTrue(h.send(DomainEvent.ActivateRequested).effects.isEmpty())
        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
    }

    @Test
    fun failedActivateWriteStaysDeactivated() {
        deactivate()
        h.send(DomainEvent.ActivateRequested)
        h.send(DomainEvent.ActiveWriteFailed(active = true))

        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
        assertNull(h.state.pendingActive)
    }

    @Test
    fun processStartWithSavedDeactivatedStartsDeactivated() {
        h.startChild(30)
        val result = h.send(DomainEvent.ProcessStarted(savedActive = false))

        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
        assertNull(h.state.childGrant)
        assertNull(h.state.parentAccess)
        assertFalse(result.effects.any { it is Effect.StartVpn || it is Effect.ApplyNetworkPolicy })
        assertTrue(result.effects.any { it is Effect.ClearParentUiAccess })
    }

    @Test
    fun processStartWithSavedActiveStartsDull() {
        deactivate()
        h.send(DomainEvent.ProcessStarted(savedActive = true))
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun invalidSettingsMeanActive() {
        deactivate()
        h.send(DomainEvent.SettingsInvalid)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun lockClearsParentAccessWhileDeactivated() {
        deactivate()
        h.pin()
        h.send(DomainEvent.DeviceLocked)
        assertNull(h.state.parentAccess)
        assertEquals(AuthorizationMode.DEACTIVATED, h.state.mode)
    }
}
