package com.kidsgarden.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Parent-facing projection of the current state (contracts/session-manager.md).
 * Contains no history, traffic statistics, or identities.
 */
data class ParentFacingSnapshot(
    val displayMode: DisplayMode,
    val authorizationMode: AuthorizationMode,
    val vpnCapability: VpnCapability,
    val callCapability: CallDetectionCapability,
    val setupComplete: Boolean,
    val dullIntensity: DullIntensity,
    /** Nonnegative remaining child play, frozen during a call pause; null without a grant. */
    val remainingPlayMillis: Long?,
    /** True only while the parent interface is unlocked by a fresh PIN entry. */
    val parentAccessActive: Boolean,
    /** Unused one-use grant permission, if any. In memory only; never persisted. */
    val grantPermission: Long?,
)

/**
 * Owns the single serialized event queue and the current state. All transition decisions
 * are delegated to [StateMachine.reduce]; this class only orders events, executes effects,
 * and publishes snapshots.
 */
class SessionManager(
    private val time: TimeProvider,
    private val policySink: NetworkPolicySink,
    private val boundaryScheduler: BoundaryScheduler,
    private val scope: CoroutineScope,
    private val callSource: CallStateSource? = null,
) {
    // Unlimited so security-relevant events (lock, revoke) are never dropped.
    private val events = Channel<DomainEvent>(Channel.UNLIMITED)

    private val _authorization = MutableStateFlow(AuthorizationState.initial())

    /** Full state, for tests and same-module coordinators. Not exposed to UI. */
    internal val authorization: StateFlow<AuthorizationState> = _authorization.asStateFlow()

    private val _state = MutableStateFlow(project(_authorization.value, time.snapshot()))
    val state: StateFlow<ParentFacingSnapshot> = _state.asStateFlow()

    private val _parentUiAccessCleared = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Emits whenever parent-interface access ends, so navigation can close protected routes. */
    val parentUiAccessCleared: SharedFlow<Unit> = _parentUiAccessCleared.asSharedFlow()

    init {
        scope.launch {
            for (event in events) process(event)
        }
    }

    /** Enqueue an event. Events are processed one at a time, in order. */
    fun dispatch(event: DomainEvent) {
        events.trySend(event)
    }

    private suspend fun process(event: DomainEvent) {
        val now = time.snapshot()
        val result = StateMachine.reduce(_authorization.value, event, now)
        _authorization.value = result.state
        for (effect in result.effects) execute(effect, result.state.generation)
        _state.value = project(result.state, now)
    }

    private suspend fun execute(effect: Effect, currentGeneration: Long) {
        // An effect computed for an older generation has no authority.
        if (effect.generation != currentGeneration) return
        when (effect) {
            is Effect.ApplyNetworkPolicy ->
                policySink.apply(effect.policy, effect.generation, effect.validUntilElapsedMillis)
            is Effect.ScheduleBoundary -> boundaryScheduler.schedule(effect.atElapsedMillis, effect.generation)
            is Effect.CancelBoundary -> boundaryScheduler.cancel()
            is Effect.ClearParentUiAccess -> _parentUiAccessCleared.tryEmit(Unit)
            // Off the event loop, so a slow query never delays lock or revoke handling.
            is Effect.ReconcileCalls -> scope.launch { dispatch(DomainEvent.CallReconciled(freshCallState())) }
            is Effect.PublishParentStatus -> Unit // snapshot is published after every event
        }
    }

    /** A failed or impossible query counts as UNAVAILABLE, which resumes countdown. */
    private suspend fun freshCallState(): CallObservation {
        val source = callSource
        val fallback = CallObservation(
            CallSourceState.UNAVAILABLE,
            _authorization.value.callMonitorGeneration,
            time.elapsedRealtimeMillis(),
        )
        if (source == null) return fallback
        return try {
            source.reconcile()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }

    private fun project(state: AuthorizationState, now: TimeSnapshot): ParentFacingSnapshot =
        ParentFacingSnapshot(
            displayMode = state.displayMode,
            authorizationMode = state.mode,
            vpnCapability = state.vpnCapability,
            callCapability = state.callCapability,
            setupComplete = state.setupComplete,
            dullIntensity = state.settings.dullIntensity,
            remainingPlayMillis = state.childGrant?.remainingMillis(now.elapsedMillis),
            parentAccessActive = state.parentAccess != null,
            grantPermission = state.parentAccess?.grantPermission,
        )
}
