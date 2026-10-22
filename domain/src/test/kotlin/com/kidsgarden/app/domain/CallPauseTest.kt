package com.kidsgarden.app.domain

import com.kidsgarden.app.domain.ReducerHarness.Companion.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telephony pause (T059, T065). */
class CallPauseTest {

    private val h = ReducerHarness()

    @Test
    fun thirtyFiveTwentyFiveLeavesExactlyTwentyFiveMinutes() {
        h.startChild(30)
        h.advanceMinutes(5)
        h.call(CallSourceState.ACTIVE)
        h.advanceMinutes(25)
        h.call(CallSourceState.INACTIVE)
        assertEquals(25 * MINUTE, h.remaining())
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
    }

    @Test
    fun pauseKeepsTheCurrentPolicyAndTheScheduledBoundary() {
        h.startChild(30)
        val boundary = h.lastBoundary()
        h.advanceMinutes(5)
        val result = h.call(CallSourceState.ACTIVE)
        assertTrue(result.effects.none { it is Effect.CancelBoundary })
        assertEquals(NetworkPolicy.Normal, h.lastPolicy())
        assertEquals(boundary, h.lastBoundary())
    }

    @Test
    fun overlappingActiveIndicationsFormOnePause() {
        h.startChild(30)
        h.advanceMinutes(5)
        h.call(CallSourceState.ACTIVE) // first SIM
        h.advanceMinutes(3)
        h.call(CallSourceState.ACTIVE) // second SIM joins; aggregate is still ACTIVE
        h.advanceMinutes(4)
        h.call(CallSourceState.INACTIVE)
        assertEquals(25 * MINUTE, h.remaining())
    }

    @Test
    fun duplicateEndCallbacksAddNothing() {
        h.startChild(30)
        h.call(CallSourceState.ACTIVE)
        h.advanceMinutes(10)
        h.call(CallSourceState.INACTIVE)
        h.call(CallSourceState.INACTIVE)
        h.call(CallSourceState.UNAVAILABLE)
        h.advanceMinutes(2)
        assertEquals(28 * MINUTE, h.remaining())
    }

    @Test
    fun ringingOnlyDoesNotPause() {
        h.startChild(30)
        h.call(CallSourceState.INACTIVE) // ringing maps to INACTIVE
        h.advanceMinutes(4)
        assertFalse(h.state.childGrant!!.isPaused)
        assertEquals(26 * MINUTE, h.remaining())
    }

    @Test
    fun callAtZeroRemainingIsDullWithNoCredit() {
        h.startChild(10)
        h.advanceMinutes(10)
        h.call(CallSourceState.ACTIVE)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
        h.advanceMinutes(5)
        h.call(CallSourceState.INACTIVE)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun grantStartedDuringACallStartsPausedWithItsFullDuration() {
        h.call(CallSourceState.ACTIVE)
        h.startChild(20)
        assertTrue(h.state.childGrant!!.isPaused)
        assertEquals(NetworkPolicy.Normal, h.lastPolicy())
        h.advanceMinutes(7)
        assertEquals(20 * MINUTE, h.remaining())
        h.call(CallSourceState.INACTIVE)
        h.advanceMinutes(1)
        assertEquals(19 * MINUTE, h.remaining())
    }

    @Test
    fun parentLockDuringACallStillRevokesParentMode() {
        h.startParent()
        h.call(CallSourceState.ACTIVE)
        h.send(DomainEvent.DeviceLocked)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun callEndNeverRevivesARevokedGrant() {
        h.startChild(30)
        h.call(CallSourceState.ACTIVE)
        h.send(DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))
        h.send(DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
        h.call(CallSourceState.INACTIVE)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun callEndNeverRevivesAReplacedGrant() {
        h.startChild(30)
        h.call(CallSourceState.ACTIVE)
        h.advanceMinutes(5)
        h.startChild(10) // started during the call: paused, full 10 minutes
        h.call(CallSourceState.INACTIVE)
        assertEquals(10 * MINUTE, h.remaining())
    }

    @Test
    fun callEndAfterRestartRevivesNothing() {
        h.startChild(30)
        h.call(CallSourceState.ACTIVE)
        h.send(DomainEvent.ProcessStarted())
        h.call(CallSourceState.INACTIVE)
        assertNull(h.state.childGrant)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun permissionLossResumesCountdown() {
        h.startChild(30)
        h.advanceMinutes(2)
        h.call(CallSourceState.ACTIVE)
        h.advanceMinutes(3)
        h.send(DomainEvent.CallCapabilityChanged(CallDetectionCapability.PERMISSION_MISSING))
        assertFalse(h.state.childGrant!!.isPaused)
        h.advanceMinutes(1)
        assertEquals(27 * MINUTE, h.remaining())
    }

    @Test
    fun staleMonitorGenerationsAreIgnored() {
        h.startChild(30)
        h.call(CallSourceState.ACTIVE, generation = 2)
        h.advanceMinutes(5)
        h.call(CallSourceState.INACTIVE, generation = 1) // from the previous registration
        assertTrue(h.state.childGrant!!.isPaused)
        assertEquals(30 * MINUTE, h.remaining())
    }

    @Test
    fun lockAndUnlockNeverAlterAPause() {
        h.startChild(30)
        h.call(CallSourceState.ACTIVE)
        h.send(DomainEvent.ScreenOff)
        h.advanceMinutes(4)
        h.send(DomainEvent.UserPresent)
        assertTrue(h.state.childGrant!!.isPaused)
        assertEquals(30 * MINUTE, h.remaining())
    }

    @Test
    fun callsDoNotPauseParentModeOrDull() {
        h.call(CallSourceState.ACTIVE)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        h.call(CallSourceState.INACTIVE)
        h.startParent()
        h.call(CallSourceState.ACTIVE)
        assertEquals(AuthorizationMode.PARENT_MODE, h.state.mode)
    }
}
