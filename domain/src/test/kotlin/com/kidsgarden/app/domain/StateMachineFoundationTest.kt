package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineFoundationTest {

    private val clock = FakeTimeProvider()
    private fun now() = clock.snapshot()

    private fun reduce(state: AuthorizationState, event: DomainEvent) =
        StateMachine.reduce(state, event, now())

    private fun grantedState(): AuthorizationState = AuthorizationState.initial().copy(
        mode = AuthorizationMode.CHILD_PLAY_SESSION,
        generation = 5,
        vpnCapability = VpnCapability.RUNNING,
        parentAccess = ParentAccess(token = 1, grantPermission = 2),
        childGrant = ChildGrant(
            durationMillis = 20 * 60_000L,
            rampMillis = 120_000L,
            startElapsedMillis = clock.elapsed,
            startEpochMillis = clock.epoch,
            target = DullIntensity.MEDIUM,
        ),
    )

    @Test
    fun initialStateIsDullWithNoGrantOrTokens() {
        val s = AuthorizationState.initial()
        assertEquals(AuthorizationMode.DULL, s.mode)
        assertNull(s.childGrant)
        assertNull(s.parentAccess)
    }

    @Test
    fun processStartRebootAndUpdateClearEverything() {
        for (event in listOf(DomainEvent.ProcessStarted(), DomainEvent.RebootObserved, DomainEvent.PackageReplaced)) {
            val before = grantedState()
            val result = reduce(before, event)
            assertEquals(AuthorizationMode.DULL, result.state.mode)
            assertNull(result.state.childGrant)
            assertNull(result.state.parentAccess)
            assertTrue(result.state.generation > before.generation)
            assertTrue(result.effects.any { it is Effect.ClearParentUiAccess })
            assertTrue(result.effects.any { it is Effect.CancelBoundary })
        }
    }

    @Test
    fun restartAppliesDullProfileWhenVpnRunning() {
        val result = reduce(grantedState(), DomainEvent.ProcessStarted())
        val policy = result.effects.filterIsInstance<Effect.ApplyNetworkPolicy>().single().policy
        assertEquals(NetworkPolicy.Dull(DullIntensity.MEDIUM), policy)
    }

    @Test
    fun restartWithoutVpnReportsUnavailableNotNormal() {
        val result = reduce(AuthorizationState.initial(), DomainEvent.ProcessStarted())
        val policy = result.effects.filterIsInstance<Effect.ApplyNetworkPolicy>().single().policy
        assertTrue(policy is NetworkPolicy.Unavailable)
        assertEquals(DisplayMode.SETUP_REQUIRED, result.state.displayMode)
    }

    @Test
    fun pinAloneGrantsParentAccessButNoPhoneAuthorization() {
        val result = reduce(AuthorizationState.initial(), DomainEvent.PinVerified)
        assertEquals(AuthorizationMode.DULL, result.state.mode)
        val access = result.state.parentAccess
        assertNotNull(access)
        assertNotNull(access!!.grantPermission)
        assertTrue(result.effects.none { it is Effect.ApplyNetworkPolicy })
    }

    @Test
    fun eachPinEntryIssuesFreshTokens() {
        val first = reduce(AuthorizationState.initial(), DomainEvent.PinVerified).state
        val second = reduce(first, DomainEvent.PinVerified).state
        assertTrue(first.parentAccess!!.token != second.parentAccess!!.token)
        assertTrue(first.parentAccess!!.grantPermission != second.parentAccess!!.grantPermission)
    }

    @Test
    fun unlockAndUiOpenNeverGrant() {
        var s = AuthorizationState.initial().copy(vpnCapability = VpnCapability.RUNNING)
        for (event in listOf(DomainEvent.UserPresent, DomainEvent.UiOpened, DomainEvent.InteractivityObserved(true))) {
            s = reduce(s, event).state
            assertEquals(AuthorizationMode.DULL, s.mode)
        }
    }

    @Test
    fun parentUiLeftClearsAccessOnly() {
        val withAccess = reduce(AuthorizationState.initial(), DomainEvent.PinVerified).state
        val result = reduce(withAccess, DomainEvent.ParentUiLeft)
        assertNull(result.state.parentAccess)
        assertEquals(withAccess.generation, result.state.generation)
        assertTrue(result.effects.any { it is Effect.ClearParentUiAccess })
    }

    @Test
    fun settingsInvalidRevokesAndResetsToSafeDefaults() {
        val result = reduce(grantedState(), DomainEvent.SettingsInvalid)
        assertEquals(AuthorizationMode.DULL, result.state.mode)
        assertNull(result.state.childGrant)
        assertEquals(DomainSettings(), result.state.settings)
    }

    @Test
    fun regressedElapsedClockRevokesBeforeHandlingEvent() {
        val state = grantedState()
        clock.elapsed -= 1 // elapsed now before the grant's start
        val result = reduce(state, DomainEvent.UiOpened)
        assertEquals(AuthorizationMode.DULL, result.state.mode)
        assertNull(result.state.childGrant)
    }

    @Test
    fun unhandledEventsAreNoOps() {
        val s = AuthorizationState.initial()
        val result = reduce(s, DomainEvent.SettingsWriteSucceeded)
        assertEquals(s, result.state)
        assertTrue(result.effects.isEmpty())
    }
}
