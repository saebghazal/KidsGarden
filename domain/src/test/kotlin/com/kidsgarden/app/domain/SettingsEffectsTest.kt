package com.kidsgarden.app.domain

import com.kidsgarden.app.domain.ReducerHarness.Companion.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings effects on authorization (T077, T078). */
class SettingsEffectsTest {

    private val h = ReducerHarness()

    private fun settings(intensity: DullIntensity) = h.state.settings.copy(dullIntensity = intensity)

    @Test
    fun intensityChangeReappliesDullImmediately() {
        val result = h.send(DomainEvent.SettingsChanged(settings(DullIntensity.GENTLE)))
        assertEquals(
            listOf(NetworkPolicy.Dull(DullIntensity.GENTLE)),
            result.effects.filterIsInstance<Effect.ApplyNetworkPolicy>().map { it.policy },
        )
    }

    @Test
    fun intensityChangeLeavesARunningSessionsTarget() {
        h.startChild(20)
        val result = h.send(DomainEvent.SettingsChanged(settings(DullIntensity.STRONG)))
        assertTrue(result.effects.none { it is Effect.ApplyNetworkPolicy })
        assertEquals(DullIntensity.MEDIUM, h.state.childGrant!!.target)
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
    }

    @Test
    fun rampLengthChangeLeavesARunningSessionsRamp() {
        h.startChild(20)
        h.send(DomainEvent.SettingsChanged(h.state.settings.copy(softLandingDurationSeconds = 60)))
        assertEquals(120_000L, h.state.childGrant!!.rampMillis)
    }

    @Test
    fun pinChangeRevokesEveryGrantAndAllAccess() {
        h.startChild(30)
        h.pin()
        val before = h.state.generation
        val result = h.send(DomainEvent.PinChanged)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
        assertNull(h.state.parentAccess)
        assertTrue(h.state.generation > before)
        assertTrue(result.effects.any { it is Effect.ClearParentUiAccess })
    }

    @Test
    fun pinChangeEndsParentMode() {
        h.startParent()
        h.send(DomainEvent.PinChanged)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.parentAccess)
    }

    @Test
    fun vpnStopEndsGrantsFirstAndKeepsTheParentInSettings() {
        h.startChild(30)
        h.advanceMinutes(5)
        h.pin()
        val before = h.state.generation
        val result = h.send(DomainEvent.VpnStopRequested)
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
        assertNotNull(h.state.parentAccess)
        assertTrue(h.state.generation > before)
        assertTrue(result.effects.any { it is Effect.CancelBoundary })
        assertTrue(result.effects.none { it is Effect.ClearParentUiAccess })
    }

    @Test
    fun vpnStopWhileDullChangesNothing() {
        h.pin()
        val before = h.state
        val result = h.send(DomainEvent.VpnStopRequested)
        assertEquals(before, h.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun vpnLossAfterStopDoesNotRestoreAnything() {
        h.startChild(30)
        h.send(DomainEvent.VpnStopRequested)
        h.send(DomainEvent.VpnCapabilityChanged(VpnCapability.UNAVAILABLE))
        h.send(DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertNull(h.state.childGrant)
    }

    @Test
    fun failedWriteLeavesAuthorizationUnchanged() {
        h.startChild(30)
        h.advanceMinutes(3)
        val before = h.state
        val result = h.send(DomainEvent.SettingsWriteFailed)
        assertEquals(before, h.state)
        assertTrue(result.effects.isEmpty())
        assertEquals(27 * MINUTE, h.remaining())
    }

    @Test
    fun successfulWriteLeavesAuthorizationUnchanged() {
        h.startParent()
        val before = h.state
        h.send(DomainEvent.SettingsWriteSucceeded)
        assertEquals(before, h.state)
    }
}
