package com.kidsgarden.app.domain

/** Next state plus the effects the platform must apply, in order. */
data class ReduceResult(
    val state: AuthorizationState,
    val effects: List<Effect>,
)

/**
 * The single transition function (Constitution IV, contracts/state-machine.md).
 * Pure: no Android, storage, or VPN access; time arrives as a [TimeSnapshot].
 *
 * Foundational transitions (T015) plus DULL/capability (T045). Story tasks add the rest:
 * Parent Mode (T051), child grants and call pause (T058, T059), Soft Landing (T070),
 * settings effects (T077).
 */
object StateMachine {

    fun reduce(state: AuthorizationState, event: DomainEvent, time: TimeSnapshot): ReduceResult {
        // Invalidity is resolved before any other event can act on stale state.
        val grant = state.childGrant
        if (grant != null && grant.consumedMillis(time.elapsedMillis) == null) {
            return revokeToDull(state)
        }

        return when (event) {
            DomainEvent.ProcessStarted,
            DomainEvent.RebootObserved,
            DomainEvent.PackageReplaced -> restart(state)

            DomainEvent.SettingsInvalid -> revokeToDull(state.copy(settings = DomainSettings()))

            DomainEvent.PinVerified -> issueParentAccess(state)

            DomainEvent.ParentUiLeft -> clearParentAccess(state)

            is DomainEvent.SettingsChanged -> settingsChanged(state, event.settings)

            is DomainEvent.CallCapabilityChanged -> publish(state.copy(callCapability = event.capability))

            is DomainEvent.InteractivityObserved ->
                unchanged(state.copy(interactiveAndUnlocked = event.interactiveAndUnlocked))

            is DomainEvent.VpnCapabilityChanged -> vpnCapabilityChanged(state, event.capability)

            DomainEvent.PinRejected,
            DomainEvent.UiOpened,
            is DomainEvent.StartParentRequested,
            is DomainEvent.StartChildRequested,
            DomainEvent.ManualParentExit,
            DomainEvent.ScreenOff,
            DomainEvent.DeviceLocked,
            DomainEvent.UserPresent,
            is DomainEvent.CallAggregateChanged,
            is DomainEvent.DeadlineObserved,
            DomainEvent.SettingsWriteSucceeded,
            DomainEvent.SettingsWriteFailed -> unchanged(state)
        }
    }

    /** Policy for DULL: the selected profile when shaping works, otherwise Unavailable. */
    fun dullPolicy(state: AuthorizationState): NetworkPolicy =
        if (state.vpnCapability == VpnCapability.RUNNING) {
            NetworkPolicy.Dull(state.settings.dullIntensity)
        } else {
            NetworkPolicy.Unavailable(REASON_VPN_UNAVAILABLE)
        }

    /** Process start, reboot, or update: DULL with no grant or tokens (FR-027). */
    private fun restart(state: AuthorizationState): ReduceResult {
        val next = AuthorizationState.initial().copy(
            generation = state.generation + 1,
            nextTokenId = state.nextTokenId,
            settings = state.settings,
            vpnCapability = state.vpnCapability,
            callCapability = state.callCapability,
            callState = state.callState,
            interactiveAndUnlocked = state.interactiveAndUnlocked,
        )
        return ReduceResult(next, dullEffects(next))
    }

    /** Ends every grant and all parent access immediately. */
    internal fun revokeToDull(state: AuthorizationState): ReduceResult {
        val next = state.copy(
            mode = AuthorizationMode.DULL,
            childGrant = null,
            parentAccess = null,
            generation = state.generation + 1,
        )
        return ReduceResult(next, dullEffects(next))
    }

    private fun dullEffects(state: AuthorizationState): List<Effect> = listOf(
        Effect.CancelBoundary(state.generation),
        Effect.ClearParentUiAccess(state.generation),
        Effect.ApplyNetworkPolicy(dullPolicy(state), state.generation),
        Effect.PublishParentStatus(state.generation),
    )

    /**
     * Live VPN capability (T045). Losing it ends any grant (DULL, SETUP_REQUIRED display);
     * regaining it re-applies the DULL profile and never creates a grant.
     */
    private fun vpnCapabilityChanged(state: AuthorizationState, capability: VpnCapability): ReduceResult {
        if (capability == state.vpnCapability) return unchanged(state)
        val next = state.copy(vpnCapability = capability)
        if (capability != VpnCapability.RUNNING && state.mode != AuthorizationMode.DULL) {
            return revokeToDull(next)
        }
        if (next.mode != AuthorizationMode.DULL) return publish(next)
        return ReduceResult(
            next,
            listOf(
                Effect.ApplyNetworkPolicy(dullPolicy(next), next.generation),
                Effect.PublishParentStatus(next.generation),
            ),
        )
    }

    /** A new DULL intensity takes effect immediately while DULL; grants keep their target. */
    private fun settingsChanged(state: AuthorizationState, settings: DomainSettings): ReduceResult {
        val next = state.copy(settings = settings)
        val intensityChanged = settings.dullIntensity != state.settings.dullIntensity
        if (next.mode != AuthorizationMode.DULL || !intensityChanged) return publish(next)
        return ReduceResult(
            next,
            listOf(
                Effect.ApplyNetworkPolicy(dullPolicy(next), next.generation),
                Effect.PublishParentStatus(next.generation),
            ),
        )
    }

    /** A correct PIN opens the parent interface only; phone authorization is unchanged. */
    private fun issueParentAccess(state: AuthorizationState): ReduceResult {
        val access = ParentAccess(token = state.nextTokenId, grantPermission = state.nextTokenId + 1)
        return publish(state.copy(parentAccess = access, nextTokenId = state.nextTokenId + 2))
    }

    private fun clearParentAccess(state: AuthorizationState): ReduceResult {
        if (state.parentAccess == null) return unchanged(state)
        val next = state.copy(parentAccess = null)
        return ReduceResult(
            next,
            listOf(Effect.ClearParentUiAccess(next.generation), Effect.PublishParentStatus(next.generation)),
        )
    }

    private fun publish(state: AuthorizationState): ReduceResult =
        ReduceResult(state, listOf(Effect.PublishParentStatus(state.generation)))

    private fun unchanged(state: AuthorizationState): ReduceResult = ReduceResult(state, emptyList())

    const val REASON_VPN_UNAVAILABLE = "vpn-unavailable"
}
