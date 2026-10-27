package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Default DULL and capability transitions (T045, T047). */
class DefaultDullTest {

    private val clock = FakeTimeProvider()

    private fun reduce(state: AuthorizationState, event: DomainEvent) =
        StateMachine.reduce(state, event, clock.snapshot())

    private val readySettings = DomainSettings(credentialPresent = true, onboardingComplete = true)

    private fun dullRunning(): AuthorizationState =
        AuthorizationState.initial().copy(settings = readySettings, vpnCapability = VpnCapability.RUNNING)

    private fun childGranted(): AuthorizationState = dullRunning().copy(
        mode = AuthorizationMode.CHILD_PLAY_SESSION,
        generation = 3,
        childGrant = ChildGrant(
            durationMillis = 30 * 60_000L,
            rampMillis = 120_000L,
            startElapsedMillis = clock.elapsed,
            startEpochMillis = clock.epoch,
            target = DullIntensity.MEDIUM,
        ),
    )

    private fun ReduceResult.appliedPolicies() =
        effects.filterIsInstance<Effect.ApplyNetworkPolicy>().map { it.policy }

    @Test
    fun unlockUserPresentUiOpenedAndPinWithoutActionStayDull() {
        var s = dullRunning()
        val events = listOf(
            DomainEvent.InteractivityObserved(true),
            DomainEvent.UserPresent,
            DomainEvent.UiOpened,
            DomainEvent.PinVerified,
            DomainEvent.UserPresent,
        )
        for (event in events) {
            val result = reduce(s, event)
            s = result.state
            assertEquals(AuthorizationMode.DULL, s.mode)
            assertNull(s.childGrant)
            assertTrue(result.appliedPolicies().none { it == NetworkPolicy.Normal })
        }
        assertEquals(DisplayMode.DULL, s.displayMode)
    }

    @Test
    fun repeatedLockUnlockCyclesNeverGrant() {
        var s = dullRunning()
        repeat(20) {
            s = reduce(s, DomainEvent.DeviceLocked).state
            s = reduce(s, DomainEvent.UserPresent).state
            assertEquals(AuthorizationMode.DULL, s.mode)
        }
    }

    @Test
    fun capabilityLossEndsGrantAndShowsSetupRequired() {
        val before = childGranted()
        val result = reduce(before, DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))
        assertEquals(AuthorizationMode.DULL, result.state.mode)
        assertNull(result.state.childGrant)
        assertNull(result.state.parentAccess)
        assertTrue(result.state.generation > before.generation)
        assertEquals(DisplayMode.SETUP_REQUIRED, result.state.displayMode)
        assertEquals(listOf(NetworkPolicy.Unavailable(StateMachine.REASON_VPN_UNAVAILABLE)), result.appliedPolicies())
    }

    @Test
    fun capabilityStartingAlsoEndsGrant() {
        val result = reduce(childGranted(), DomainEvent.VpnCapabilityChanged(VpnCapability.STARTING))
        assertEquals(AuthorizationMode.DULL, result.state.mode)
        assertNull(result.state.childGrant)
    }

    @Test
    fun capabilityLossWhileDullKeepsDullAndReportsUnavailable() {
        val result = reduce(dullRunning(), DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))
        assertEquals(AuthorizationMode.DULL, result.state.mode)
        assertEquals(DisplayMode.SETUP_REQUIRED, result.state.displayMode)
        assertTrue(result.appliedPolicies().single() is NetworkPolicy.Unavailable)
    }

    @Test
    fun capabilityRestoreAppliesDullAndCreatesNoGrant() {
        val lost = AuthorizationState.initial().copy(settings = readySettings)
        val result = reduce(lost, DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
        assertEquals(AuthorizationMode.DULL, result.state.mode)
        assertNull(result.state.childGrant)
        assertNull(result.state.parentAccess)
        assertEquals(listOf(NetworkPolicy.Dull(DullIntensity.MEDIUM)), result.appliedPolicies())
        assertEquals(DisplayMode.DULL, result.state.displayMode)
    }

    @Test
    fun repeatedCapabilityReportIsNoOp() {
        val s = dullRunning()
        val result = reduce(s, DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
        assertEquals(s, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun runningVpnWithoutFinishedSetupStillShowsSetupRequired() {
        val s = AuthorizationState.initial().copy(
            settings = DomainSettings(credentialPresent = true, onboardingComplete = false),
            vpnCapability = VpnCapability.RUNNING,
        )
        assertEquals(DisplayMode.SETUP_REQUIRED, s.displayMode)
    }

    @Test
    fun settingsInvalidGivesDullWithSafeDefaults() {
        val result = reduce(childGranted(), DomainEvent.SettingsInvalid)
        assertEquals(AuthorizationMode.DULL, result.state.mode)
        assertNull(result.state.childGrant)
        assertEquals(DomainSettings(), result.state.settings)
        assertFalse(result.state.setupComplete)
        assertEquals(listOf(NetworkPolicy.Dull(DullIntensity.MEDIUM)), result.appliedPolicies())
    }

    @Test
    fun intensityChangeWhileDullReappliesSelectedProfile() {
        val result = reduce(
            dullRunning(),
            DomainEvent.SettingsChanged(readySettings.copy(dullIntensity = DullIntensity.STRONG)),
        )
        assertEquals(listOf(NetworkPolicy.Dull(DullIntensity.STRONG)), result.appliedPolicies())
    }

    @Test
    fun intensityChangeDuringGrantDoesNotTouchNetwork() {
        val result = reduce(
            childGranted(),
            DomainEvent.SettingsChanged(readySettings.copy(dullIntensity = DullIntensity.STRONG)),
        )
        assertTrue(result.appliedPolicies().isEmpty())
        assertEquals(DullIntensity.MEDIUM, result.state.childGrant!!.target)
    }
}
