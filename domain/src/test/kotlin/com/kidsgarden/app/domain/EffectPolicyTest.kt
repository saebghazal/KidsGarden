package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Effect policy and dulling capability in the reducer (T113, FR-032, FR-034). */
class EffectPolicyTest {

    private val allWorking = ScreenEffect.entries.associateWith { EffectAccess.WORKING }

    private fun harness(effects: Set<ScreenEffect> = ScreenEffect.entries.toSet(), vpn: VpnCapability = VpnCapability.RUNNING) =
        ReducerHarness().apply {
            state = state.copy(
                settings = state.settings.copy(enabledEffects = effects),
                effectAccess = allWorking,
                vpnCapability = vpn,
                screenOn = true,
            )
        }

    private fun ReducerHarness.lastEffectPolicy(): Effect.ApplyEffectPolicy? =
        effects.filterIsInstance<Effect.ApplyEffectPolicy>().lastOrNull()

    @Test
    fun onInDullWhenActiveWithoutCall() {
        val h = harness()
        assertEquals(EffectPolicy.On(ScreenEffect.entries.toSet(), DullIntensity.MEDIUM), h.state.effectPolicy)
    }

    @Test
    fun offInEveryGrantMode() {
        val h = harness()
        h.startParent()
        assertEquals(EffectPolicy.Off, h.lastEffectPolicy()!!.policy)

        h.send(DomainEvent.ManualParentExit)
        assertTrue(h.lastEffectPolicy()!!.policy is EffectPolicy.On)

        h.startChild(10)
        assertEquals(EffectPolicy.Off, h.lastEffectPolicy()!!.policy)
        h.advanceMinutes(9)
        h.fireBoundary()
        assertEquals(AuthorizationMode.SOFT_LANDING, h.state.mode)
        assertEquals(EffectPolicy.Off, h.state.effectPolicy)

        // Expiry back to DULL turns effects on, starting the cycle from none.
        h.advanceMinutes(1)
        h.fireBoundary()
        assertEquals(AuthorizationMode.DULL, h.state.mode)
        assertTrue(h.lastEffectPolicy()!!.policy is EffectPolicy.On)
    }

    @Test
    fun offDuringPhoneCallInDullAndBackAfter() {
        val h = harness()
        h.call(CallSourceState.ACTIVE)
        assertEquals(EffectPolicy.Off, h.lastEffectPolicy()!!.policy)
        h.call(CallSourceState.INACTIVE)
        assertTrue(h.lastEffectPolicy()!!.policy is EffectPolicy.On)
    }

    @Test
    fun offOnDeactivate() {
        val h = harness()
        h.pin()
        h.send(DomainEvent.DeactivateRequested(confirmed = true))
        h.send(DomainEvent.ActiveWriteSucceeded(active = false))
        assertEquals(EffectPolicy.Off, h.lastEffectPolicy()!!.policy)
        assertEquals(EffectPolicy.Off, h.state.effectPolicy)
    }

    @Test
    fun screenChangesAreReportedWhileOn() {
        val h = harness()
        h.send(DomainEvent.ScreenOff)
        assertFalse(h.lastEffectPolicy()!!.screenOn)
        assertTrue(h.lastEffectPolicy()!!.policy is EffectPolicy.On)
        h.send(DomainEvent.ScreenOn)
        assertTrue(h.lastEffectPolicy()!!.screenOn)
    }

    @Test
    fun noEffectPolicyNoiseWhenNoEffectEnabled() {
        val h = harness(effects = emptySet())
        h.send(DomainEvent.ScreenOff)
        h.send(DomainEvent.ScreenOn)
        h.startParent()
        assertTrue(h.effects.none { it is Effect.ApplyEffectPolicy })
    }

    @Test
    fun onlyEffectsWithAccessAreApplied() {
        val h = harness()
        h.send(
            DomainEvent.EffectCapabilityChanged(
                mapOf(
                    ScreenEffect.GREY_SCREEN to EffectAccess.ACCESS_MISSING,
                    ScreenEffect.LOWER_VOLUME to EffectAccess.WORKING,
                    ScreenEffect.LOWER_BRIGHTNESS to EffectAccess.ACCESS_MISSING,
                ),
            ),
        )
        assertEquals(EffectPolicy.On(setOf(ScreenEffect.LOWER_VOLUME), DullIntensity.MEDIUM), h.lastEffectPolicy()!!.policy)
    }

    @Test
    fun intensityChangeUpdatesEffects() {
        val h = harness()
        h.send(DomainEvent.SettingsChanged(h.state.settings.copy(dullIntensity = DullIntensity.STRONG)))
        assertEquals(DullIntensity.STRONG, (h.lastEffectPolicy()!!.policy as EffectPolicy.On).intensity)
    }

    @Test
    fun setupRequiredOnlyWhenNoMethodWorks() {
        val h = harness(vpn = VpnCapability.UNAVAILABLE)
        assertEquals(DisplayMode.DULL, h.state.displayMode)

        h.send(DomainEvent.EffectCapabilityChanged(ScreenEffect.entries.associateWith { EffectAccess.ACCESS_MISSING }))
        assertEquals(DisplayMode.SETUP_REQUIRED, h.state.displayMode)

        h.send(DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
        assertEquals(DisplayMode.DULL, h.state.displayMode)
    }

    @Test
    fun grantsAreOfferedWhenOnlyAnEffectWorks() {
        val h = harness(effects = setOf(ScreenEffect.LOWER_VOLUME), vpn = VpnCapability.UNAVAILABLE)
        h.startChild(30)
        assertEquals(AuthorizationMode.CHILD_PLAY_SESSION, h.state.mode)
        h.send(DomainEvent.ManualParentExit)

        val none = harness(effects = emptySet(), vpn = VpnCapability.UNAVAILABLE)
        none.startParent()
        assertEquals(AuthorizationMode.DULL, none.state.mode)
    }

    @Test
    fun methodSwitchesChangeTheEffectSet() {
        val h = harness(effects = emptySet())
        h.send(DomainEvent.MethodSwitchesChanged(networkEnabled = true, effects = setOf(ScreenEffect.GREY_SCREEN)))
        assertEquals(EffectPolicy.On(setOf(ScreenEffect.GREY_SCREEN), DullIntensity.MEDIUM), h.lastEffectPolicy()!!.policy)
    }
}
