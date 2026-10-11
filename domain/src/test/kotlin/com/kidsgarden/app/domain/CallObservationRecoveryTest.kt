package com.kidsgarden.app.domain

import com.kidsgarden.app.domain.ReducerHarness.Companion.MINUTE
import com.kidsgarden.app.domain.ReducerHarness.Companion.RAMP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lost end-of-call callback and the mandatory checkpoint (T059, T066). */
class CallObservationRecoveryTest {

    private val h = ReducerHarness()

    /** 30-minute grant, 5 minutes played, then a call whose end callback is lost. */
    private fun pausedWithLostEnd(): Long {
        h.startChild(30)
        h.advanceMinutes(5)
        h.call(CallSourceState.ACTIVE)
        val checkpoint = h.lastBoundary()!!
        // The call really ended, but no INACTIVE callback arrives. Time reaches the checkpoint.
        h.clock.elapsed = checkpoint
        return checkpoint
    }

    @Test
    fun checkpointAsksForAFreshQuery() {
        pausedWithLostEnd()
        val result = h.fireBoundary()
        assertTrue(result.effects.any { it is Effect.ReconcileCalls })
        assertTrue(h.state.childGrant!!.isPaused)
    }

    @Test
    fun cachedActiveCannotRenewThePause() {
        pausedWithLostEnd()
        h.fireBoundary()
        val boundaries = h.effects.count { it is Effect.ScheduleBoundary }
        h.call(CallSourceState.ACTIVE) // stale cached callback
        assertEquals(boundaries, h.effects.count { it is Effect.ScheduleBoundary })
    }

    @Test
    fun freshInactiveResumesTheSameRemainder() {
        pausedWithLostEnd()
        h.fireBoundary()
        h.reconciled(CallSourceState.INACTIVE)
        assertFalse(h.state.childGrant!!.isPaused)
        assertEquals(25 * MINUTE, h.remaining())
        assertEquals(h.clock.elapsed + 25 * MINUTE - RAMP, h.lastBoundary())
    }

    @Test
    fun freshUnavailableAlsoResumes() {
        pausedWithLostEnd()
        h.fireBoundary()
        h.reconciled(CallSourceState.UNAVAILABLE)
        assertFalse(h.state.childGrant!!.isPaused)
        assertEquals(25 * MINUTE, h.remaining())
    }

    @Test
    fun freshActiveOnlySetsTheNextBoundedCheckpoint() {
        val checkpoint = pausedWithLostEnd()
        h.fireBoundary()
        h.reconciled(CallSourceState.ACTIVE)
        assertTrue(h.state.childGrant!!.isPaused)
        assertEquals(25 * MINUTE, h.remaining())
        val next = h.lastBoundary()!!
        assertEquals(checkpoint + 25 * MINUTE - RAMP, next)
        // The paused policy lapses shortly after that checkpoint unless renewed again.
        val validUntil = h.lastPolicyEffect()!!.validUntilElapsedMillis
        assertNotNull(validUntil)
        assertTrue(validUntil!! > next && validUntil - next <= 60_000L)
    }

    @Test
    fun noPathFreezesTimeIndefinitely() {
        h.startChild(30)
        h.advanceMinutes(5)
        h.call(CallSourceState.ACTIVE)
        // Even with repeated fresh ACTIVE results, a checkpoint is always scheduled.
        repeat(5) {
            h.clock.elapsed = h.lastBoundary()!!
            val result = h.fireBoundary()
            assertTrue(result.effects.any { it is Effect.ReconcileCalls })
            h.reconciled(CallSourceState.ACTIVE)
            assertNotNull(h.lastBoundary())
        }
        // The moment a fresh query cannot confirm the call, countdown resumes.
        h.clock.elapsed = h.lastBoundary()!!
        h.fireBoundary()
        h.reconciled(CallSourceState.UNAVAILABLE)
        h.advanceMinutes(1)
        assertEquals(24 * MINUTE, h.remaining())
    }

    @Test
    fun recoveryNeverCreatesAGrant() {
        h.reconciled(CallSourceState.INACTIVE)
        h.reconciled(CallSourceState.ACTIVE)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertEquals(null, h.state.childGrant)
    }
}
