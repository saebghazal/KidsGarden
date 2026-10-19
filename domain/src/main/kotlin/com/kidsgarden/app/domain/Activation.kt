package com.kidsgarden.app.domain

import com.kidsgarden.app.domain.StateMachine.clearParentAccess
import com.kidsgarden.app.domain.StateMachine.freshState
import com.kidsgarden.app.domain.StateMachine.issueParentAccess
import com.kidsgarden.app.domain.StateMachine.publish
import com.kidsgarden.app.domain.StateMachine.unchanged

/**
 * The parent's Active/Deactivated switch (FR-031, contracts/state-machine.md). Part of the
 * reducer: called only from [StateMachine.reduce].
 *
 * Both directions commit only after `active` is durably saved: a request records the value
 * being saved and emits [Effect.PersistActive]; SessionManager reports the outcome as
 * [DomainEvent.ActiveWriteSucceeded] or [DomainEvent.ActiveWriteFailed]. A failed write
 * leaves the previous state in effect.
 */
internal object Activation {

    /** Confirmed Deactivate from the parent interface, from any Active state. */
    fun requestDeactivate(state: AuthorizationState, confirmed: Boolean): ReduceResult {
        if (!confirmed || state.parentAccess == null || state.pendingActive != null) return unchanged(state)
        val next = state.copy(pendingActive = false)
        return ReduceResult(next, listOf(Effect.PersistActive(active = false, generation = next.generation)))
    }

    /**
     * `active = false` is saved: end every grant, pause and grant permission, stop the VPN.
     * The parent stays in the interface to see the result; a pending one-use permission is
     * discarded so no grant can follow.
     */
    fun enterDeactivated(state: AuthorizationState): ReduceResult {
        val next = state.copy(
            mode = AuthorizationMode.DEACTIVATED,
            childGrant = null,
            rampStage = null,
            parentAccess = state.parentAccess?.copy(grantPermission = null),
            pendingActive = null,
            generation = state.generation + 1,
        )
        return ReduceResult(
            next,
            listOf(
                Effect.CancelBoundary(next.generation),
                Effect.StopVpn(next.generation),
                Effect.PublishParentStatus(next.generation),
            ),
        )
    }

    /** A new process whose valid saved settings say Deactivated: no grant, no VPN. */
    fun startDeactivated(state: AuthorizationState): ReduceResult {
        val next = freshState(state, AuthorizationMode.DEACTIVATED)
        return ReduceResult(
            next,
            listOf(
                Effect.CancelBoundary(next.generation),
                Effect.ClearParentUiAccess(next.generation),
                Effect.PublishParentStatus(next.generation),
            ),
        )
    }

    /**
     * Events while DEACTIVATED. Nothing re-enters DULL except a saved Activate (or invalid
     * settings, which always mean Active). Grant, lock, screen, call, boundary and VPN events
     * change nothing about authorization; live observations are still recorded so that state
     * is current after Activate. Parent-interface access follows its usual rules.
     */
    fun whileDeactivated(state: AuthorizationState, event: DomainEvent): ReduceResult = when (event) {
        is DomainEvent.ProcessStarted ->
            if (event.savedActive) StateMachine.restart(state) else startDeactivated(state)

        DomainEvent.RebootObserved,
        DomainEvent.PackageReplaced -> startDeactivated(state)

        // Invalid settings were replaced by defaults, which are Active.
        DomainEvent.SettingsInvalid -> StateMachine.restart(state.copy(settings = DomainSettings()))

        is DomainEvent.SettingsChanged -> publish(state.copy(settings = event.settings))

        is DomainEvent.MethodSwitchesChanged ->
            publish(state.copy(settings = state.settings.copy(networkEnabled = event.networkEnabled, enabledEffects = event.effects)))

        DomainEvent.PinVerified -> issueParentAccess(state)

        DomainEvent.PinChanged,
        DomainEvent.ParentUiLeft -> clearParentAccess(state)

        DomainEvent.ScreenOff -> clearParentAccess(state.copy(screenOn = false, interactiveAndUnlocked = false))
        DomainEvent.DeviceLocked -> clearParentAccess(state.copy(interactiveAndUnlocked = false))
        DomainEvent.ScreenOn -> unchanged(state.copy(screenOn = true))
        DomainEvent.UserPresent -> unchanged(state.copy(screenOn = true, interactiveAndUnlocked = true))
        is DomainEvent.InteractivityObserved ->
            if (event.interactiveAndUnlocked) unchanged(state.copy(screenOn = true, interactiveAndUnlocked = true))
            else clearParentAccess(state.copy(interactiveAndUnlocked = false))

        is DomainEvent.ActivateRequested -> requestActivate(state, event.grantPermission)

        is DomainEvent.ActiveWriteSucceeded ->
            if (event.active && state.pendingActive == true) activate(state) else unchanged(state.copy(pendingActive = null))

        is DomainEvent.ActiveWriteFailed -> publish(state.copy(pendingActive = null))

        // Recorded silently: the VPN stays stopped and nothing is dulled.
        is DomainEvent.VpnCapabilityChanged -> unchanged(state.copy(vpnCapability = event.capability))
        is DomainEvent.CallCapabilityChanged -> unchanged(state.copy(callCapability = event.capability))
        is DomainEvent.CallAggregateChanged -> recordCall(state, event.observation)
        is DomainEvent.CallReconciled -> recordCall(state, event.observation)
        is DomainEvent.EffectCapabilityChanged -> publish(state.copy(effectAccess = event.access))

        DomainEvent.UiOpened,
        DomainEvent.PinRejected,
        DomainEvent.SettingsWriteSucceeded,
        DomainEvent.SettingsWriteFailed,
        DomainEvent.VpnStopRequested,
        DomainEvent.ManualParentExit,
        is DomainEvent.DeactivateRequested,
        is DomainEvent.StartParentRequested,
        is DomainEvent.StartChildRequested,
        is DomainEvent.DeadlineObserved -> unchanged(state)
    }

    /** Activate needs the unused one-use permission from a fresh PIN entry; it consumes it. */
    private fun requestActivate(state: AuthorizationState, permission: Long): ReduceResult {
        val access = state.parentAccess ?: return unchanged(state)
        if (access.grantPermission != permission || state.pendingActive != null) return unchanged(state)
        val next = state.copy(pendingActive = true, parentAccess = access.copy(grantPermission = null))
        return ReduceResult(next, listOf(Effect.PersistActive(active = true, generation = next.generation)))
    }

    /**
     * `active = true` is saved: DULL with no grant, VPN restarted (SETUP_REQUIRED shows if it
     * cannot run). Parent access ends, so any later grant needs a new PIN entry.
     */
    private fun activate(state: AuthorizationState): ReduceResult {
        val next = state.copy(
            mode = AuthorizationMode.DULL,
            parentAccess = null,
            pendingActive = null,
            generation = state.generation + 1,
        )
        return ReduceResult(
            next,
            listOf(
                Effect.StartVpn(next.generation),
                Effect.ClearParentUiAccess(next.generation),
                Effect.ApplyNetworkPolicy(StateMachine.dullPolicy(next), next.generation),
                Effect.PublishParentStatus(next.generation),
            ),
        )
    }

    private fun recordCall(state: AuthorizationState, observation: CallObservation): ReduceResult {
        if (observation.monitorGeneration < state.callMonitorGeneration) return unchanged(state)
        return unchanged(state.copy(callState = observation.state, callMonitorGeneration = observation.monitorGeneration))
    }
}
