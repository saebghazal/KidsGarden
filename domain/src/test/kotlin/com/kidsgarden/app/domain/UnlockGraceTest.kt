package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unlock grace (FR-037; owner changes 2026-10-25 and 2026-10-27) with fake time. Defaults:
 * 5-minute grace, 2-minute fade, 60-minute window.
 */
class UnlockGraceTest {

    private val minute = ReducerHarness.MINUTE

    private fun harness(enabled: Boolean = true) = ReducerHarness().apply {
        state = state.copy(
            settings = state.settings.copy(unlockGraceEnabled = enabled),
            screenOn = false,
            interactiveAndUnlocked = false,
        )
    }

    private fun ReducerHarness.unlock() = send(DomainEvent.UserPresent)

    private fun ReducerHarness.lock() = send(DomainEvent.ScreenOff)

    private fun ReducerHarness.grace() = state.childGrant?.takeIf { it.unlockGrace }

    private fun ReducerHarness.persisted() = effects.filterIsInstance<Effect.PersistUnlockGraceStart>().map { it.epochMillis }

    @Test
    fun firstUnlockOpensAWindowWithNormalNetworkAndNoMessage() {
        val h = harness()
        val result = h.unlock()

        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
        assertTrue(h.grace() != null)
        assertEquals(NetworkPolicy.Normal, h.lastPolicy())
        assertEquals(listOf<Long?>(h.clock.epoch), h.persisted())
        assertEquals(7 * minute, h.remaining())
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
    fun lockPausesTheGraceAndTheNextUnlockContinuesIt() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(2)
        h.lock()
        // Locked: the phone is dull and the rest of the grace is kept.
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertEquals(NetworkPolicy.Dull(DullIntensity.MEDIUM), h.lastPolicy())
        assertEquals(5 * minute, h.state.graceWindow!!.remainingMillis)

        h.advanceMinutes(20) // time spent locked is not used
        h.unlock()
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
        assertEquals(5 * minute, h.remaining())
        assertEquals(NetworkPolicy.Normal, h.lastPolicy())
        // The window is not reopened: it stays fixed from the first unlock.
        assertEquals(1, h.persisted().size)
    }

    @Test
    fun pauseInsideTheFadeResumesAtTheSameStage() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(5)
        h.advanceMillis(50_000L) // into the fade, past its first stage
        h.fireBoundary()
        val stage = h.state.rampStage
        h.lock()
        h.advanceMinutes(10)
        h.unlock()
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)
        assertEquals(stage, h.state.rampStage)
        assertEquals(70_000L, h.remaining())
    }

    @Test
    fun usedUpGraceKeepsLaterUnlocksInTheWindowDull() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(7)
        h.fireBoundary()
        assertEquals(AuthorizationMode.DULL, h.state.mode)

        h.lock()
        h.advanceMinutes(30)
        h.unlock()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun unusedGraceIsLostWhenTheWindowEndsAndTheNextUnlockGetsAFreshOne() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(1)
        h.lock() // 6 minutes left in the window
        h.advanceMinutes(60) // window ends while locked
        h.unlock()
        // A new window with a full, fresh grace.
        assertEquals(7 * minute, h.remaining())
        assertEquals(2, h.persisted().size)
    }

    @Test
    fun windowEndingWhileGraceRunsStartsTheFadeAtOnce() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(1)
        h.lock()
        h.advanceMinutes(58) // 1 minute of the window left, 6 minutes of grace banked
        h.unlock()
        assertEquals(6 * minute, h.remaining())
        h.advanceMinutes(1) // window end
        h.fireBoundary()
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)
        assertEquals(2 * minute, h.remaining())
        h.advanceMinutes(2)
        h.fireBoundary()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        // The window has ended, so the next unlock opens a fresh one.
        h.lock()
        h.unlock()
        assertEquals(7 * minute, h.remaining())
    }

    @Test
    fun startingParentModeClearsTheWindow() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(7)
        h.fireBoundary()
        h.send(DomainEvent.InteractivityObserved(interactiveAndUnlocked = true))
        h.startParent()
        assertNull(h.state.graceWindow)
        assertEquals(null, h.persisted().last())

        h.lock() // ends Parent Mode
        h.unlock()
        assertEquals(7 * minute, h.remaining())
    }

    @Test
    fun turningKidsGardenOffAndOnClearsTheWindow() {
        val h = harness()
        h.unlock()
        h.advanceMinutes(7)
        h.fireBoundary()
        h.pin()
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        h.send(DomainEvent.ActiveWriteSucceeded(active = false))
        h.send(DomainEvent.ActivateRequested)
        h.send(DomainEvent.ActiveWriteSucceeded(active = true))
        assertNull(h.state.graceWindow)
        assertEquals(null, h.persisted().last())

        h.lock()
        h.unlock()
        assertEquals(7 * minute, h.remaining())
    }

    @Test
    fun aRestartInsideTheWindowGivesNoNewGrace() {
        val h = harness()
        h.unlock()
        val started = h.persisted().single()!!
        h.state = AuthorizationState.initial().copy(
            settings = h.state.settings.copy(lastUnlockGraceEpochMillis = started),
            vpnCapability = VpnCapability.RUNNING,
        )
        h.advanceMinutes(10)
        h.unlock()
        assertEquals(AuthorizationMode.DULL, h.state.mode)

        h.lock()
        h.advanceMinutes(50)
        h.unlock() // the saved window has ended
        assertEquals(7 * minute, h.remaining())
    }

    @Test
    fun clockSetBackCountsAsInsideTheWindow() {
        val h = harness()
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
    fun noGraceDuringPlayTime() {
        val h = harness()
        h.send(DomainEvent.InteractivityObserved(interactiveAndUnlocked = true))
        h.startChild(30)
        h.unlock()
        assertFalse(h.state.childGrant!!.unlockGrace)
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
    fun windowFromSavedStart() {
        assertNull(UnlockGrace.windowFromSaved(null, 0L, 0L, 1_000L))
        assertNull(UnlockGrace.windowFromSaved(1_000L, 2_000L, 5L, 1_000L))
        assertEquals(GraceWindow(1_000L, 505L, 0L), UnlockGrace.windowFromSaved(1_000L, 1_500L, 5L, 1_000L))
        // Clock set back: open for at most one rest time from now.
        assertEquals(GraceWindow(9_000L, 1_005L, 0L), UnlockGrace.windowFromSaved(9_000L, 1_000L, 5L, 1_000L))
    }
}
