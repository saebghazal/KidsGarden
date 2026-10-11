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
 * Foundational transitions (T015), DULL/capability (T045), Parent Mode (T051), child
 * grants (T058), telephony pause (T059), Soft Landing stages (T070), and settings effects
 * (T077).
 */
object StateMachine {

    fun reduce(state: AuthorizationState, event: DomainEvent, time: TimeSnapshot): ReduceResult {
        val e = time.elapsedMillis
        // Invalidity is resolved before any other event can act on stale state.
        val grant = state.childGrant
        if (grant != null && grant.consumedMillis(e) == null) {
            return revokeToDull(state)
        }
        // Time is settled first: a grant that reached a boundary while nothing was watching
        // (sleep, missed wake-up) changes mode or expires before the event is handled.
        val settled = settleGrant(state, e)
        val handled = handle(settled.state, event, time)
        return ReduceResult(handled.state, settled.effects + handled.effects)
    }

    private fun handle(state: AuthorizationState, event: DomainEvent, time: TimeSnapshot): ReduceResult {
        val e = time.elapsedMillis
        return when (event) {
            DomainEvent.ProcessStarted,
            DomainEvent.RebootObserved,
            DomainEvent.PackageReplaced -> restart(state)

            DomainEvent.SettingsInvalid -> revokeToDull(state.copy(settings = DomainSettings()))

            DomainEvent.PinVerified -> issueParentAccess(state)

            DomainEvent.PinChanged -> revokeToDull(state)

            // Grants end first; the parent stays in Settings to finish stopping the VPN.
            DomainEvent.VpnStopRequested ->
                if (state.mode == AuthorizationMode.DULL) unchanged(state) else expireToDull(state)

            DomainEvent.ParentUiLeft -> clearParentAccess(state)

            is DomainEvent.SettingsChanged -> settingsChanged(state, event.settings)

            is DomainEvent.CallCapabilityChanged -> callCapabilityChanged(state, event.capability, e)

            is DomainEvent.InteractivityObserved ->
                if (event.interactiveAndUnlocked) unchanged(state.copy(interactiveAndUnlocked = true))
                else screenOffOrLocked(state)

            is DomainEvent.StartParentRequested -> startParent(state, event.grantPermission)

            is DomainEvent.StartChildRequested -> startChild(state, event.grantPermission, event.durationMinutes, time)

            DomainEvent.ScreenOff,
            DomainEvent.DeviceLocked -> screenOffOrLocked(state)

            DomainEvent.UserPresent -> userPresent(state)

            DomainEvent.ManualParentExit ->
                if (state.mode == AuthorizationMode.PARENT_MODE) revokeToDull(state) else unchanged(state)

            is DomainEvent.VpnCapabilityChanged -> vpnCapabilityChanged(state, event.capability)

            is DomainEvent.CallAggregateChanged -> callObserved(state, event.observation, e, fresh = false)

            is DomainEvent.CallReconciled -> callObserved(state, event.observation, e, fresh = true)

            is DomainEvent.DeadlineObserved -> deadlineObserved(state, event.boundaryGeneration, e)

            DomainEvent.PinRejected,
            DomainEvent.UiOpened,
            DomainEvent.SettingsWriteSucceeded,
            DomainEvent.SettingsWriteFailed -> unchanged(state)
        }
    }

    /** Policy for DULL: the selected profile when shaping works, otherwise Unavailable. */
    fun dullPolicy(state: AuthorizationState): NetworkPolicy =
        DullModePolicy.policyFor(state.copy(mode = AuthorizationMode.DULL, childGrant = null), 0L)

    /** Process start, reboot, or update: DULL with no grant or tokens (FR-027). */
    private fun restart(state: AuthorizationState): ReduceResult {
        val next = AuthorizationState.initial().copy(
            generation = state.generation + 1,
            nextTokenId = state.nextTokenId,
            settings = state.settings,
            vpnCapability = state.vpnCapability,
            callCapability = state.callCapability,
            callState = state.callState,
            callMonitorGeneration = state.callMonitorGeneration,
            interactiveAndUnlocked = state.interactiveAndUnlocked,
        )
        return ReduceResult(next, dullEffects(next))
    }

    /** Ends every grant and all parent access immediately. */
    internal fun revokeToDull(state: AuthorizationState): ReduceResult {
        val next = state.copy(
            mode = AuthorizationMode.DULL,
            childGrant = null,
            rampStage = null,
            parentAccess = null,
            generation = state.generation + 1,
        )
        return ReduceResult(next, dullEffects(next))
    }

    /**
     * The grant ran out, or the parent ended it from Settings: discard it and return to DULL.
     * Unlike a revocation, a parent in the parent interface stays there.
     */
    private fun expireToDull(state: AuthorizationState): ReduceResult {
        val next = state.copy(
            mode = AuthorizationMode.DULL,
            childGrant = null,
            rampStage = null,
            generation = state.generation + 1,
        )
        return ReduceResult(
            next,
            listOf(
                Effect.CancelBoundary(next.generation),
                Effect.ApplyNetworkPolicy(dullPolicy(next), next.generation),
                Effect.PublishParentStatus(next.generation),
            ),
        )
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

    /**
     * Parent Mode (T051). Requires the unused one-use permission from the current PIN entry,
     * an interactive unlocked phone, finished setup, and a running VPN. Commits in memory in
     * this one step: consumes the permission, bumps the generation, and applies Normal. Any
     * failed prerequisite leaves everything unchanged, including the permission.
     */
    private fun startParent(state: AuthorizationState, permission: Long): ReduceResult {
        val access = state.parentAccess ?: return unchanged(state)
        val eligible = access.grantPermission == permission &&
            state.interactiveAndUnlocked &&
            state.setupComplete &&
            state.vpnCapability == VpnCapability.RUNNING
        if (!eligible) return unchanged(state)
        val next = state.copy(
            mode = AuthorizationMode.PARENT_MODE,
            childGrant = null,
            rampStage = null,
            parentAccess = access.copy(grantPermission = null),
            generation = state.generation + 1,
        )
        return ReduceResult(
            next,
            listOf(
                Effect.CancelBoundary(next.generation),
                Effect.ApplyNetworkPolicy(NetworkPolicy.Normal, next.generation),
                Effect.PublishParentStatus(next.generation),
            ),
        )
    }

    /**
     * Screen off or lock: Parent Mode ends, and parent-interface access always clears.
     * Because events are serialized, a lock processed before a start makes that start fail.
     */
    private fun screenOffOrLocked(state: AuthorizationState): ReduceResult {
        val locked = state.copy(interactiveAndUnlocked = false)
        if (locked.mode == AuthorizationMode.PARENT_MODE) return revokeToDull(locked)
        return clearParentAccess(locked)
    }

    /** Unlock never restores Parent Mode; a surviving Parent Mode grant is revoked defensively. */
    private fun userPresent(state: AuthorizationState): ReduceResult {
        val unlocked = state.copy(interactiveAndUnlocked = true)
        if (unlocked.mode == AuthorizationMode.PARENT_MODE) return revokeToDull(unlocked)
        return unchanged(unlocked)
    }

    /**
     * Child play (T058). Exactly 10, 20, or 30 minutes with the fresh one-use permission and
     * the same prerequisites as Parent Mode. Captures the current intensity and ramp length,
     * replaces any existing grant (no stacking), and clears parent access in the same step.
     * Started during a phone call, the grant begins paused with its full duration.
     */
    private fun startChild(state: AuthorizationState, permission: Long, minutes: Int, time: TimeSnapshot): ReduceResult {
        val access = state.parentAccess ?: return unchanged(state)
        val eligible = minutes in CHILD_DURATIONS_MINUTES &&
            access.grantPermission == permission &&
            state.interactiveAndUnlocked &&
            state.setupComplete &&
            state.vpnCapability == VpnCapability.RUNNING
        if (!eligible) return unchanged(state)
        val e = time.elapsedMillis
        val grant = ChildGrant(
            durationMillis = minutes * MILLIS_PER_MINUTE,
            rampMillis = state.settings.softLandingDurationSeconds * MILLIS_PER_SECOND,
            startElapsedMillis = e,
            startEpochMillis = time.epochMillis,
            target = state.settings.dullIntensity,
            pauseStartElapsedMillis = if (state.callState == CallSourceState.ACTIVE) e else null,
        )
        val next = state.copy(
            mode = AuthorizationMode.CHILD_PLAY_SESSION,
            childGrant = grant,
            rampStage = null,
            parentAccess = null,
            generation = state.generation + 1,
        )
        return ReduceResult(
            next,
            listOfNotNull(
                Effect.ClearParentUiAccess(next.generation),
                grantPolicyEffect(next, e),
                boundaryEffect(next, e),
                Effect.PublishParentStatus(next.generation),
            ),
        )
    }

    /**
     * Moves a grant to the mode its consumed play implies at [e]. C >= D discards the grant
     * (DULL). Paused grants have frozen play, so they never move here.
     */
    private fun settleGrant(state: AuthorizationState, e: Long): ReduceResult {
        val grant = state.childGrant ?: return unchanged(state)
        val mode = grant.modeAt(e) ?: return revokeToDull(state)
        if (mode == AuthorizationMode.DULL) return expireToDull(state)
        val stage = if (mode == AuthorizationMode.SOFT_LANDING) grant.rampStageAt(e) else null
        // A late wake that skipped stages lands directly on the current one.
        if (mode == state.mode && stage == state.rampStage) return unchanged(state)
        val next = state.copy(mode = mode, rampStage = stage)
        return ReduceResult(
            next,
            listOfNotNull(
                grantPolicyEffect(next, e),
                boundaryEffect(next, e),
                Effect.PublishParentStatus(next.generation),
            ),
        )
    }

    /** Network intent for the current grant: Normal during play, the staged ramp after. */
    private fun grantPolicy(state: AuthorizationState, e: Long): NetworkPolicy = DullModePolicy.policyFor(state, e)

    /** The grant's policy, valid only until its next boundary. */
    private fun grantPolicyEffect(state: AuthorizationState, e: Long): Effect =
        Effect.ApplyNetworkPolicy(grantPolicy(state, e), state.generation, state.childGrant?.nextBoundaryElapsed(e))

    /**
     * While paused the current policy is kept, valid until the mandatory checkpoint plus a
     * grace period for the fresh query. Only a fresh ACTIVE result extends it again.
     */
    private fun pausedPolicy(state: AuthorizationState, e: Long): ReduceResult {
        val checkpoint = state.childGrant?.nextBoundaryElapsed(e)
        val validUntil = checkpoint?.let { if (it > Long.MAX_VALUE - CHECKPOINT_GRACE_MILLIS) null else it + CHECKPOINT_GRACE_MILLIS }
        return ReduceResult(
            state,
            listOfNotNull(
                Effect.ApplyNetworkPolicy(grantPolicy(state, e), state.generation, validUntil ?: checkpoint),
                checkpoint?.let { Effect.ScheduleBoundary(it, state.generation) },
                Effect.PublishParentStatus(state.generation),
            ),
        )
    }

    private fun boundaryEffect(state: AuthorizationState, e: Long): Effect? =
        state.childGrant?.nextBoundaryElapsed(e)?.let { Effect.ScheduleBoundary(it, state.generation) }

    /**
     * A scheduled boundary fired. Mode changes were already settled. While paused this is
     * the mandatory checkpoint: only a fresh query ([DomainEvent.CallReconciled]) can keep
     * the pause, so cached ACTIVE state never renews it.
     */
    private fun deadlineObserved(state: AuthorizationState, boundaryGeneration: Long, e: Long): ReduceResult {
        if (boundaryGeneration != state.generation) return unchanged(state)
        val grant = state.childGrant ?: return unchanged(state)
        if (grant.isPaused) return ReduceResult(state, listOf(Effect.ReconcileCalls(state.generation)))
        return ReduceResult(state, listOfNotNull(boundaryEffect(state, e)))
    }

    /**
     * Telephony pause (T059). ACTIVE on an unexpired grant opens one union pause; repeats add
     * nothing. INACTIVE or UNAVAILABLE closes it once. Only a fresh query renews an open
     * pause, and then only until the next projected boundary. The scheduled boundary is
     * never cancelled by a pause. Stale monitor generations are ignored.
     */
    private fun callObserved(state: AuthorizationState, observation: CallObservation, e: Long, fresh: Boolean): ReduceResult {
        if (observation.monitorGeneration < state.callMonitorGeneration) return unchanged(state)
        val recorded = state.copy(callState = observation.state, callMonitorGeneration = observation.monitorGeneration)
        val grant = recorded.childGrant ?: return unchanged(recorded)
        return when (observation.state) {
            CallSourceState.ACTIVE ->
                if (grant.isPaused) {
                    if (fresh) pausedPolicy(recorded, e) else unchanged(recorded)
                } else {
                    val paused = grant.pausedAt(e) ?: return revokeToDull(recorded)
                    pausedPolicy(recorded.copy(childGrant = paused), e)
                }
            CallSourceState.INACTIVE,
            CallSourceState.UNAVAILABLE ->
                if (grant.isPaused) closePause(recorded, e) else unchanged(recorded)
        }
    }

    /** Losing call detection ends any open pause; countdown continues. */
    private fun callCapabilityChanged(state: AuthorizationState, capability: CallDetectionCapability, e: Long): ReduceResult {
        val recorded = state.copy(callCapability = capability)
        if (capability != CallDetectionCapability.AVAILABLE && recorded.childGrant?.isPaused == true) {
            return closePause(recorded.copy(callState = CallSourceState.UNAVAILABLE), e)
        }
        return publish(recorded)
    }

    private fun closePause(state: AuthorizationState, e: Long): ReduceResult {
        val resumed = state.childGrant?.resumedAt(e) ?: return revokeToDull(state)
        val next = state.copy(childGrant = resumed)
        return ReduceResult(
            next,
            listOfNotNull(
                grantPolicyEffect(next, e),
                boundaryEffect(next, e),
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

    /** The only durations a parent can give (FR-009). */
    val CHILD_DURATIONS_MINUTES: Set<Int> = setOf(10, 20, 30)

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_SECOND = 1_000L

    /** Time allowed for the checkpoint's fresh call query before a paused policy lapses. */
    private const val CHECKPOINT_GRACE_MILLIS = 60_000L
}
