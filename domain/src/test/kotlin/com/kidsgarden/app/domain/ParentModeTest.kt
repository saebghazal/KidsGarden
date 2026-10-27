@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kidsgarden.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parent Mode transitions (T051, T052). */
class ParentModeTest {

    private val clock = FakeTimeProvider()

    private fun reduce(state: AuthorizationState, event: DomainEvent) =
        StateMachine.reduce(state, event, clock.snapshot())

    /** DULL, setup finished, VPN running, phone unlocked, PIN just entered. */
    private fun readyWithAccess(): AuthorizationState {
        val ready = AuthorizationState.initial().copy(
            settings = DomainSettings(credentialPresent = true, onboardingComplete = true),
            vpnCapability = VpnCapability.RUNNING,
            interactiveAndUnlocked = true,
        )
        return reduce(ready, DomainEvent.PinVerified).state
    }

    private fun permission(state: AuthorizationState): Long = state.parentAccess!!.grantPermission!!

    private fun inParentMode(): AuthorizationState {
        val s = readyWithAccess()
        return reduce(s, DomainEvent.StartParentRequested(permission(s))).state
    }

    private fun ReduceResult.appliedPolicies() =
        effects.filterIsInstance<Effect.ApplyNetworkPolicy>().map { it.policy }

    @Test
    fun freshPermissionStartsParentModeWithNormal() {
        val before = readyWithAccess()
        val result = reduce(before, DomainEvent.StartParentRequested(permission(before)))
        assertEquals(AuthorizationMode.PARENT_MODE, result.state.mode)
        assertEquals(DisplayMode.PARENT_MODE, result.state.displayMode)
        assertTrue(result.state.generation > before.generation)
        assertEquals(listOf(NetworkPolicy.Normal), result.appliedPolicies())
        // The permission is consumed; interface access stays for the status screen.
        assertNotNull(result.state.parentAccess)
        assertNull(result.state.parentAccess!!.grantPermission)
    }

    @Test
    fun reusedPermissionIsRejected() {
        val before = readyWithAccess()
        val used = permission(before)
        val inMode = reduce(before, DomainEvent.StartParentRequested(used)).state
        val exited = reduce(inMode, DomainEvent.ManualParentExit).state
        val again = reduce(exited, DomainEvent.StartParentRequested(used))
        assertEquals(AuthorizationMode.DULL, again.state.mode)
        assertTrue(again.appliedPolicies().none { it == NetworkPolicy.Normal })
    }

    @Test
    fun wrongOrMissingPermissionIsRejected() {
        val s = readyWithAccess()
        assertEquals(AuthorizationMode.DULL, reduce(s, DomainEvent.StartParentRequested(permission(s) + 99)).state.mode)
        val noAccess = s.copy(parentAccess = null)
        assertEquals(AuthorizationMode.DULL, reduce(noAccess, DomainEvent.StartParentRequested(permission(s))).state.mode)
    }

    @Test
    fun prerequisitesAreRequiredAndFailureKeepsThePermission() {
        val s = readyWithAccess()
        val cases = listOf(
            s.copy(interactiveAndUnlocked = false),
            s.copy(vpnCapability = VpnCapability.UNAVAILABLE),
            s.copy(vpnCapability = VpnCapability.STARTING),
            s.copy(settings = s.settings.copy(onboardingComplete = false)),
        )
        for (case in cases) {
            val result = reduce(case, DomainEvent.StartParentRequested(permission(s)))
            assertEquals(AuthorizationMode.DULL, result.state.mode)
            assertEquals(permission(s), result.state.parentAccess!!.grantPermission)
            assertTrue(result.effects.isEmpty())
        }
    }

    @Test
    fun appSwitchingKeepsTheGrantAndParentAccess() {
        // Leaving and reopening the app is no event that ends anything (clarification 2026-10-27).
        val result = reduce(inParentMode(), DomainEvent.UiOpened)
        assertEquals(AuthorizationMode.PARENT_MODE, result.state.mode)
        assertNotNull(result.state.parentAccess)
        assertTrue(result.appliedPolicies().isEmpty())
    }

    @Test
    fun endingParentModeInsideTheAppKeepsParentAccess() {
        for (event in listOf(DomainEvent.ManualParentExit, DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))) {
            val result = reduce(inParentMode(), event)
            assertEquals("after $event", AuthorizationMode.DULL, result.state.mode)
            assertNotNull("after $event", result.state.parentAccess)
            assertNotNull("after $event", result.state.parentAccess!!.grantPermission)
            assertTrue("after $event", result.appliedPolicies().none { it == NetworkPolicy.Normal })
        }
    }

    @Test
    fun everyRevocationPathReturnsToDull() {
        val paths = listOf(
            DomainEvent.ScreenOff,
            DomainEvent.DeviceLocked,
            DomainEvent.UserPresent,
            DomainEvent.InteractivityObserved(false),
            DomainEvent.SettingsInvalid,
            DomainEvent.ProcessStarted(),
            DomainEvent.RebootObserved,
            DomainEvent.PackageReplaced,
        )
        for (event in paths) {
            val before = inParentMode()
            val result = reduce(before, event)
            assertEquals("after $event", AuthorizationMode.DULL, result.state.mode)
            assertNull("after $event", result.state.parentAccess)
            assertTrue("after $event", result.state.generation > before.generation)
            assertTrue("after $event", result.appliedPolicies().none { it == NetworkPolicy.Normal })
        }
    }

    @Test
    fun manualExitNeedsNoPin() {
        val s = inParentMode().copy(parentAccess = null) // parent left the app first
        val result = reduce(s, DomainEvent.ManualParentExit)
        assertEquals(AuthorizationMode.DULL, result.state.mode)
    }

    @Test
    fun reopeningTheAppInParentModeNeedsNoPin() {
        val result = reduce(inParentMode(), DomainEvent.UiOpened)
        assertEquals(AuthorizationMode.PARENT_MODE, result.state.mode)
        assertNotNull(result.state.parentAccess)
        assertNotNull(result.state.parentAccess!!.grantPermission)
        assertTrue(result.appliedPolicies().isEmpty())
    }

    @Test
    fun reopeningInParentModeCanHandThePhoneToAChild() {
        val opened = reduce(inParentMode(), DomainEvent.UiOpened).state
        val result = reduce(opened, DomainEvent.StartChildRequested(permission(opened), 40))
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, result.state.mode)
        assertNull(result.state.parentAccess)
    }

    @Test
    fun openingTheAppAfterALockNeedsThePin() {
        val locked = reduce(inParentMode(), DomainEvent.ScreenOff).state
        assertNull(reduce(locked, DomainEvent.UiOpened).state.parentAccess)
    }

    @Test
    fun unlockNeverRestoresParentMode() {
        var s = reduce(inParentMode(), DomainEvent.ScreenOff).state
        s = reduce(s, DomainEvent.UserPresent).state
        s = reduce(s, DomainEvent.InteractivityObserved(true)).state
        assertEquals(AuthorizationMode.DULL, s.mode)
        assertNull(s.parentAccess)
    }

    @Test
    fun lockProcessedBeforeAPendingStartWins() {
        val s = readyWithAccess()
        val permission = permission(s)
        val locked = reduce(s, DomainEvent.DeviceLocked).state
        val start = reduce(locked, DomainEvent.StartParentRequested(permission))
        assertEquals(AuthorizationMode.DULL, start.state.mode)
        assertTrue(start.appliedPolicies().isEmpty())
    }

    @Test
    fun callsNeverRevokeParentMode() {
        var s = inParentMode()
        val active = CallObservation(CallSourceState.ACTIVE, monitorGeneration = 1, elapsedMillis = clock.elapsed)
        val ended = CallObservation(CallSourceState.INACTIVE, monitorGeneration = 1, elapsedMillis = clock.elapsed)
        s = reduce(s, DomainEvent.CallAggregateChanged(active)).state
        assertEquals(AuthorizationMode.PARENT_MODE, s.mode)
        s = reduce(s, DomainEvent.CallAggregateChanged(ended)).state
        assertEquals(AuthorizationMode.PARENT_MODE, s.mode)
    }

    @Test
    fun staleGenerationEffectsAreIgnored() = runTest {
        val applied = mutableListOf<Pair<NetworkPolicy, Long>>()
        val sink = object : NetworkPolicySink {
            override suspend fun apply(policy: NetworkPolicy, generation: Long, validUntilElapsedMillis: Long?) {
                applied += policy to generation
            }
        }
        val scheduler = object : BoundaryScheduler {
            override fun schedule(atElapsedMillis: Long, generation: Long) = Unit
            override fun cancel() = Unit
        }
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val manager = SessionManager(clock, sink, scheduler, scope)
        manager.dispatch(DomainEvent.SettingsChanged(DomainSettings(credentialPresent = true, onboardingComplete = true)))
        manager.dispatch(DomainEvent.InteractivityObserved(true))
        manager.dispatch(DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
        manager.dispatch(DomainEvent.PinVerified)
        val permission = manager.state.value.grantPermission!!
        manager.dispatch(DomainEvent.StartParentRequested(permission))
        manager.dispatch(DomainEvent.ScreenOff)

        // Every policy was applied with the generation current when it was computed, and the
        // last one is DULL: nothing from the revoked Parent Mode generation came after it.
        val normal = applied.indexOfFirst { it.first == NetworkPolicy.Normal }
        assertTrue(normal >= 0)
        val last = applied.last()
        assertEquals(NetworkPolicy.Dull(DullIntensity.MEDIUM), last.first)
        assertTrue(last.second > applied[normal].second)
        assertEquals(AuthorizationMode.DULL, manager.state.value.authorizationMode)
    }
}
