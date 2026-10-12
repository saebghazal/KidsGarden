package com.kidsgarden.app.domain

import com.kidsgarden.app.domain.ReducerHarness.Companion.MINUTE
import com.kidsgarden.app.domain.ReducerHarness.Companion.RAMP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Child grants (T058, T064). */
class ChildSessionTest {

    private val h = ReducerHarness()

    @Test
    fun eachDurationStartsWithFullTimeNormalAndNextBoundary() {
        for (minutes in 10..120 step 10) {
            val h = ReducerHarness()
            val start = h.clock.elapsed
            val result = h.startChild(minutes)
            assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
            assertEquals(minutes * MINUTE, h.remaining())
            assertEquals(NetworkPolicy.Normal, h.lastPolicy())
            assertEquals(start + minutes * MINUTE - RAMP, h.lastBoundary())
            assertTrue(result.effects.any { it is Effect.ClearParentUiAccess })
            assertNull(h.state.parentAccess)
        }
    }

    @Test
    fun relaxedPolicyIsBoundedByTheNextBoundary() {
        val start = h.clock.elapsed
        h.startChild(10)
        assertEquals(start + 10 * MINUTE - RAMP, h.lastPolicyEffect()!!.validUntilElapsedMillis)
    }

    @Test
    fun onlyTenMinuteStepsFromTenToOneHundredTwenty() {
        val permission = h.pin()
        for (minutes in listOf(0, 5, 15, 31, 65, 130, -10)) {
            h.send(DomainEvent.StartChildRequested(permission, minutes))
            assertEquals(AuthorizationMode.DULL, h.state.mode)
        }
        assertEquals(permission, h.state.parentAccess!!.grantPermission)
    }

    @Test
    fun duplicateStartIsRejected() {
        val permission = h.pin()
        h.send(DomainEvent.StartChildRequested(permission, 20))
        h.advanceMinutes(5)
        val duplicate = h.send(DomainEvent.StartChildRequested(permission, 30))
        assertTrue(duplicate.effects.isEmpty())
        assertEquals(15 * MINUTE, h.remaining())
    }

    @Test
    fun capturesIntensityAndRampAtStart() {
        h.state = h.state.copy(settings = h.state.settings.copy(dullIntensity = DullIntensity.STRONG, softLandingDurationSeconds = 60))
        h.startChild(10)
        val grant = h.state.childGrant!!
        assertEquals(DullIntensity.STRONG, grant.target)
        assertEquals(60_000L, grant.rampMillis)
    }

    @Test
    fun lockedMinutesCount() {
        h.startChild(10)
        h.send(DomainEvent.ScreenOff)
        h.advanceMinutes(5)
        h.send(DomainEvent.UserPresent)
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
        assertEquals(5 * MINUTE, h.remaining())
    }

    @Test
    fun lockAndUnlockChangeNothingButElapsedTime() {
        h.startChild(30)
        repeat(10) {
            h.send(DomainEvent.DeviceLocked)
            h.advanceMinutes(1)
            h.send(DomainEvent.UserPresent)
            h.send(DomainEvent.InteractivityObserved(true))
        }
        assertEquals(20 * MINUTE, h.remaining())
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
    }

    @Test
    fun expiryWhileLockedIsDullAtNextEvaluation() {
        h.startChild(10)
        h.send(DomainEvent.ScreenOff)
        h.advanceMinutes(11) // no boundary fired while asleep
        val result = h.send(DomainEvent.UserPresent)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
        assertEquals(NetworkPolicy.Dull(DullIntensity.MEDIUM), result.effects.filterIsInstance<Effect.ApplyNetworkPolicy>().last().policy)
    }

    @Test
    fun newGrantReplacesWithoutStacking() {
        h.startChild(30)
        h.advanceMinutes(10)
        val before = h.state.generation
        h.startChild(10)
        assertEquals(10 * MINUTE, h.remaining())
        assertTrue(h.state.generation > before)
    }

    @Test
    fun parentModeReplacesAChildGrant() {
        h.startChild(30)
        h.startParent()
        assertEquals(AuthorizationMode.PARENT_MODE, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun wallClockAndTimezoneChangesAddNothing() {
        h.startChild(20)
        h.advanceMinutes(5)
        h.clock.shiftWallClock(-3 * 60 * MINUTE)
        h.send(DomainEvent.UiOpened)
        assertEquals(15 * MINUTE, h.remaining())
        h.clock.shiftWallClock(+10 * 60 * MINUTE)
        h.send(DomainEvent.UiOpened)
        assertEquals(15 * MINUTE, h.remaining())
    }

    @Test
    fun regressedElapsedClockRevokes() {
        h.startChild(20)
        h.clock.elapsed -= 1
        h.send(DomainEvent.UiOpened)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun overflowingAccountingRevokes() {
        h.startChild(20)
        h.state = h.state.copy(childGrant = h.state.childGrant!!.copy(completedPauseMillis = Long.MAX_VALUE))
        h.send(DomainEvent.UiOpened)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun exactBoundaryInstants() {
        h.startChild(10)
        h.advanceMillis(10 * MINUTE - RAMP - 1)
        h.fireBoundary()
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)

        h.advanceMillis(1) // exactly D − L
        h.fireBoundary()
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)
        assertTrue(h.lastPolicy() is NetworkPolicy.SoftLanding)

        h.advanceMillis(RAMP - 1) // D − 1 ms
        h.fireBoundary()
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)

        h.advanceMillis(1) // exactly D
        h.fireBoundary()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun staleBoundaryIsIgnored() {
        h.startChild(30)
        val oldGeneration = h.state.generation
        h.startChild(30)
        val result = h.send(DomainEvent.DeadlineObserved(oldGeneration))
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun startRequiresTheSamePrerequisitesAsParentMode() {
        val permission = h.pin()
        val cases = listOf(
            h.state.copy(interactiveAndUnlocked = false),
            h.state.copy(vpnCapability = VpnCapability.UNAVAILABLE),
            h.state.copy(settings = h.state.settings.copy(onboardingComplete = false)),
        )
        for (case in cases) {
            val result = StateMachine.reduce(case, DomainEvent.StartChildRequested(permission, 10), h.clock.snapshot())
            assertEquals(AuthorizationMode.DULL, result.state.mode)
        }
    }

    @Test
    fun expiryKeepsParentInterfaceAccess() {
        h.startChild(10)
        h.pin() // parent checks the dashboard during play
        h.advanceMinutes(10)
        h.fireBoundary()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNotNull(h.state.parentAccess)
    }
}
