package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unlock grace (FR-037, owner change 2026-10-25) with fake time. */
class UnlockGraceTest {

    private val minute = ReducerHarness.MINUTE

    /** Grace on with the defaults: 5 minutes, 2-minute fade, 60-minute rest. */
    private fun harness(enabled: Boolean = true, lastStart: Long? = null) = ReducerHarness().apply {
        state = state.copy(
            settings = state.settings.copy(unlockGraceEnabled = enabled, lastUnlockGraceEpochMillis = lastStart),
            screenOn = false,
            interactiveAndUnlocked = false,
        )
    }

    private fun ReducerHarness.unlock() = send(DomainEvent.UserPresent)

    @Test
    fun unlockStartsGraceWithNormalNetworkAndNoMessage() {
        val h = harness()
        val result = h.unlock()

        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
        assertTrue(h.state.childGrant!!.unlockGrace)
        assertEquals(NetworkPolicy.Normal, h.lastPolicy())
        assertTrue(result.effects.any { it is Effect.PersistUnlockGraceStart })
        // Nothing opens the parent interface or addresses the child.
        assertNull(h.state.parentAccess)
        assertFalse(result.effects.any { it is Effect.ClearParentUiAccess })
    }

    @Test
    fun graceFadesThroughTheRampThenDull() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(5)
        h.fireBoundary()
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)
        assertTrue(h.lastPolicy() is NetworkPolicy.SoftLanding)

        h.advanceMinutes(2)
        h.fireBoundary()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertEquals(NetworkPolicy.Dull(DullIntensity.MEDIUM), h.lastPolicy())
        assertNull(h.state.childGrant)
    }

    @Test
    fun lockingDoesNotPauseOrResetTheGrace() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(3)
        h.send(DomainEvent.DeviceLocked)
        h.send(DomainEvent.ScreenOff)
        h.advanceMinutes(1)
        h.unlock()
        // Same grace, still counting: 1 minute of grace plus the 2-minute fade left.
        assertEquals(3 * minute, h.remaining())
    }

    @Test
    fun secondUnlockWithinRestStaysDull() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(7)
        h.fireBoundary()
        assertEquals(AuthorizationMode.DULL, h.state.mode)

        h.send(DomainEvent.ScreenOff)
        h.advanceMinutes(30)
        h.unlock()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun unlockAfterRestStartsANewGrace() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(60)
        h.fireBoundary()
        h.send(DomainEvent.ScreenOff)
        h.unlock()
        assertTrue(h.state.childGrant!!.unlockGrace)
    }

    @Test
    fun savedStartSurvivesARestart() {
        val h = harness()
        h.unlock()
        val started = h.effects.filterIsInstance<Effect.PersistUnlockGraceStart>().single().epochMillis
        h.send(DomainEvent.ProcessStarted())
        // The new process only knows the saved start time.
        h.state = h.state.copy(
            lastUnlockGraceEpochMillis = null,
            settings = h.state.settings.copy(lastUnlockGraceEpochMillis = started),
        )
        h.advanceMinutes(10)
        h.unlock()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun clockSetBackCountsAsResting() {
        val h = harness()
        // The saved start lies in the future: the wall clock was set back since.
        h.state = h.state.copy(settings = h.state.settings.copy(lastUnlockGraceEpochMillis = h.clock.epoch + 10 * 60 * minute))
        h.unlock()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
    }

    @Test
    fun noGraceWhenOffDeactivatedOrUnprotected() {
        val off = harness(enabled = false)
        off.unlock()
        assertEquals(AuthorizationMode.DULL, off.state.mode)

        val unprotected = harness().apply { state = state.copy(vpnCapability = VpnCapability.UNAVAILABLE) }
        unprotected.unlock()
        assertEquals(AuthorizationMode.DULL, unprotected.state.mode)

        val deactivated = harness()
        deactivated.pin()
        deactivated.send(DomainEvent.DeactivateRequested(confirmed = true))
        deactivated.send(DomainEvent.ActiveWriteSucceeded(active = false))
        deactivated.unlock()
        assertEquals(AuthorizationMode.DEACTIVATED, deactivated.state.mode)
    }

    @Test
    fun noGraceDuringPlayTimeOrParentMode() {
        val h = harness()
        h.send(DomainEvent.InteractivityObserved(interactiveAndUnlocked = true))
        h.startChild(30)
        h.unlock()
        assertFalse(h.state.childGrant!!.unlockGrace)

        val p = harness()
        p.send(DomainEvent.InteractivityObserved(interactiveAndUnlocked = true))
        p.startParent()
        assertEquals(AuthorizationMode.PARENT_MODE, p.state.mode)
        p.unlock() // unlock revokes Parent Mode defensively and must not hand out a grace
        assertEquals(AuthorizationMode.DULL, p.state.mode)
    }

    @Test
    fun phoneCallPausesTheGrace() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(2)
        h.call(CallSourceState.ACTIVE)
        h.advanceMinutes(20)
        h.call(CallSourceState.INACTIVE)
        assertEquals(5 * minute, h.remaining())
    }

    @Test
    fun parentGrantReplacesTheGrace() {
        val h = harness()
        h.unlock()
        assertTrue(h.state.childGrant!!.unlockGrace)
        h.startChild(30)
        assertFalse(h.state.childGrant!!.unlockGrace)
        assertEquals(30 * minute, h.remaining())
    }

    @Test
    fun effectsStayOffDuringGraceAndStartAfter() {
        val h = harness().apply {
            state = state.copy(
                settings = state.settings.copy(enabledEffects = setOf(ScreenEffect.GREY_SCREEN)),
                effectAccess = mapOf(ScreenEffect.GREY_SCREEN to EffectAccess.WORKING),
            )
        }
        h.unlock()
        assertEquals(EffectPolicy.Off, h.state.effectPolicy)
        h.advanceMinutes(7)
        h.fireBoundary()
        assertTrue(h.state.effectPolicy is EffectPolicy.On)
    }

    @Test
    fun restRule() {
        assertTrue(UnlockGrace.restOver(null, 0L, 1L))
        assertFalse(UnlockGrace.restOver(1_000L, 1_500L, 1_000L))
        assertTrue(UnlockGrace.restOver(1_000L, 2_000L, 1_000L))
        assertFalse(UnlockGrace.restOver(5_000L, 1_000L, 1_000L))
    }
}
