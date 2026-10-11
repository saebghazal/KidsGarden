package com.kidsgarden.app.domain

/**
 * Drives [StateMachine.reduce] with a fake clock and records every effect, so session and
 * call tests read like the scenarios in the spec.
 */
internal class ReducerHarness(val clock: FakeTimeProvider = FakeTimeProvider()) {

    /** DULL, setup finished, VPN running, phone unlocked, default 120-second ramp. */
    var state: AuthorizationState = AuthorizationState.initial().copy(
        settings = DomainSettings(credentialPresent = true, onboardingComplete = true),
        vpnCapability = VpnCapability.RUNNING,
        interactiveAndUnlocked = true,
    )

    val effects = mutableListOf<Effect>()
    private var monitorGeneration = 1L

    fun send(event: DomainEvent): ReduceResult {
        val result = StateMachine.reduce(state, event, clock.snapshot())
        state = result.state
        effects += result.effects
        return result
    }

    /** Enters the PIN and returns the fresh one-use permission. */
    fun pin(): Long {
        send(DomainEvent.PinVerified)
        return state.parentAccess!!.grantPermission!!
    }

    fun startChild(minutes: Int): ReduceResult = send(DomainEvent.StartChildRequested(pin(), minutes))

    fun startParent(): ReduceResult = send(DomainEvent.StartParentRequested(pin()))

    fun advanceMinutes(minutes: Long) = clock.advance(minutes * MINUTE)

    fun advanceMillis(millis: Long) = clock.advance(millis)

    fun remaining(): Long? = state.childGrant?.remainingMillis(clock.elapsed)

    fun observation(callState: CallSourceState, generation: Long = monitorGeneration) =
        CallObservation(callState, generation, clock.elapsed)

    /** A listener callback (may be cached; cannot renew a pause). */
    fun call(callState: CallSourceState, generation: Long = monitorGeneration): ReduceResult =
        send(DomainEvent.CallAggregateChanged(observation(callState, generation)))

    /** The result of a fresh current-state query. */
    fun reconciled(callState: CallSourceState, generation: Long = monitorGeneration): ReduceResult =
        send(DomainEvent.CallReconciled(observation(callState, generation)))

    /** Fires the most recently scheduled boundary for the current generation. */
    fun fireBoundary(): ReduceResult = send(DomainEvent.DeadlineObserved(state.generation))

    fun lastBoundary(): Long? = effects.filterIsInstance<Effect.ScheduleBoundary>().lastOrNull()?.atElapsedMillis

    fun lastPolicy(): NetworkPolicy? = effects.filterIsInstance<Effect.ApplyNetworkPolicy>().lastOrNull()?.policy

    fun lastPolicyEffect(): Effect.ApplyNetworkPolicy? = effects.filterIsInstance<Effect.ApplyNetworkPolicy>().lastOrNull()

    companion object {
        const val MINUTE = 60_000L
        const val RAMP = 120_000L
    }
}
