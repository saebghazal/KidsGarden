package com.kidsgarden.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    /** True while a phone call pauses play. */
    val playPaused: Boolean,
    /** Elapsed time at which [remainingPlayMillis] was computed, for a display-only ticker. */
    val snapshotElapsedMillis: Long,
    /** True only while the parent interface is unlocked by a fresh PIN entry. */
    val parentAccessActive: Boolean,
    /** Unused one-use grant permission, if any. In memory only; never persisted. */
    val grantPermission: Long?,
    /** False while Deactivated (FR-031). */
    val active: Boolean = true,
    /** Network dulling switch. */
    val networkEnabled: Boolean = true,
    /** Effects the parent enabled (FR-036). */
    val enabledEffects: Set<ScreenEffect> = emptySet(),
    /** Live access per effect; absent means unknown. */
    val effectAccess: Map<ScreenEffect, EffectAccess> = emptyMap(),
    /**
     * Active with network dulling on and setup complete, yet the VPN is not running: for
     * example Android stopped Kids Garden in the background (FR-033). Derived, never recorded.
     */
    val backgroundStopped: Boolean = false,
)

/** Parent-facing failures of a requested switch (contracts/session-manager.md). */
sealed class SessionError(message: String) : Exception(message) {
    /** The settings write failed; the previous state stays in effect. */
    class StorageUnavailable : SessionError("settings could not be saved")

    /** Missing access, missing confirmation, stale permission, or already in that state. */
    class StaleRequest : SessionError("request not accepted")
}

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
    private val activationStore: ActivationStore? = null,
    private val vpnLifecycle: VpnLifecycle? = null,
    private val effectSink: EffectPolicySink? = null,
) {
    /** An event plus, for a requested switch, where to report its outcome. */
    private class Envelope(val event: DomainEvent, val reply: CompletableDeferred<Result<Unit>>? = null)

    // Unlimited so security-relevant events (lock, revoke) are never dropped.
    private val events = Channel<Envelope>(Channel.UNLIMITED)

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
            for (envelope in events) process(envelope)
        }
    }

    /** Enqueue an event. Events are processed one at a time, in order. */
    fun dispatch(event: DomainEvent) {
        events.trySend(Envelope(event))
    }

    /**
     * Confirmed Deactivate (FR-031). Saves `active = false` first; only after success are
     * grants ended and the VPN stopped. Needs current parent access.
     */
    suspend fun deactivate(confirmed: Boolean): Result<Unit> = request(DomainEvent.DeactivateRequested(confirmed))

    /**
     * Activate with the one-use permission from a fresh PIN entry. Saves `active = true`,
     * then DULL with the VPN restarted. Never creates a grant.
     */
    suspend fun activate(grantPermission: Long): Result<Unit> = request(DomainEvent.ActivateRequested(grantPermission))

    private suspend fun request(event: DomainEvent): Result<Unit> {
        val reply = CompletableDeferred<Result<Unit>>()
        events.trySend(Envelope(event, reply))
        return reply.await()
    }

    private suspend fun process(envelope: Envelope) {
        val event = envelope.event
        val now = time.snapshot()
        val result = StateMachine.reduce(_authorization.value, event, now)
        _authorization.value = result.state
        var persisting = false
        for (effect in result.effects) {
            if (effect is Effect.PersistActive && effect.generation == result.state.generation) {
                persisting = true
                persistActive(effect.active, envelope.reply)
            } else {
                execute(effect, result.state.generation)
            }
        }
        _state.value = project(result.state, now)
        val reply = envelope.reply ?: return
        when (event) {
            is DomainEvent.ActiveWriteSucceeded -> reply.complete(Result.success(Unit))
            is DomainEvent.ActiveWriteFailed -> reply.complete(Result.failure(SessionError.StorageUnavailable()))
            else -> if (!persisting) reply.complete(Result.failure(SessionError.StaleRequest()))
        }
    }

    /** Off the event loop: a slow write never delays lock or revoke handling. */
    private fun persistActive(active: Boolean, reply: CompletableDeferred<Result<Unit>>?) {
        scope.launch {
            val saved = try {
                activationStore?.saveActive(active) == true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            val outcome = if (saved) DomainEvent.ActiveWriteSucceeded(active) else DomainEvent.ActiveWriteFailed(active)
            events.trySend(Envelope(outcome, reply))
        }
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
            is Effect.PersistActive -> Unit // handled in process()
            is Effect.StartVpn -> vpnLifecycle?.startVpn()
            is Effect.StopVpn -> vpnLifecycle?.stopVpn()
            is Effect.ApplyEffectPolicy -> effectSink?.apply(effect.policy, effect.screenOn, effect.generation)
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
            playPaused = state.childGrant?.isPaused == true,
            snapshotElapsedMillis = now.elapsedMillis,
            parentAccessActive = state.parentAccess != null,
            grantPermission = state.parentAccess?.grantPermission,
            active = state.mode != AuthorizationMode.DEACTIVATED,
            networkEnabled = state.settings.networkEnabled,
            enabledEffects = state.settings.enabledEffects,
            effectAccess = state.effectAccess,
            backgroundStopped = state.mode != AuthorizationMode.DEACTIVATED &&
                state.settings.networkEnabled &&
                state.setupComplete &&
                state.vpnCapability == VpnCapability.UNAVAILABLE,
        )
}
