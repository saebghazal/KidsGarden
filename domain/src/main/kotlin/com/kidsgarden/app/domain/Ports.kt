package com.kidsgarden.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * Boundaries implemented by Android adapters in `:app`. The domain depends only on these
 * interfaces, never on Android classes (Constitution IV).
 */

/** Schedules one wake-up at an elapsed-realtime deadline (research R5). */
interface BoundaryScheduler {
    fun schedule(atElapsedMillis: Long, generation: Long)
    fun cancel()
}

/** Telephony call observations (contracts/call-state-monitor.md). */
interface CallStateSource {
    val observations: Flow<CallObservation>

    /** Fresh current-state query; never answers from a cached ACTIVE value. */
    suspend fun reconcile(): CallObservation
}

/**
 * Applies network policy; implemented by VpnDullingController. [validUntilElapsedMillis]
 * bounds a relaxed policy: after that elapsed time the engine must not keep forwarding
 * under it, even if no newer policy has arrived.
 */
interface NetworkPolicySink {
    suspend fun apply(policy: NetworkPolicy, generation: Long, validUntilElapsedMillis: Long? = null)
}

/** Read side of persisted parent settings. Writes go through the app's repository. */
interface SettingsStore {
    val settings: Flow<DomainSettings>
}

/** Saves the parent's Active/Deactivated choice (FR-031). True only after a durable write. */
fun interface ActivationStore {
    suspend fun saveActive(active: Boolean): Boolean
}

/** Starts and stops VPN dulling for Activate and Deactivate. */
interface VpnLifecycle {
    fun startVpn()
    fun stopVpn()
}

/** Applies screen and sound effects; implemented by ScreenEffectsController. */
fun interface EffectPolicySink {
    fun apply(policy: EffectPolicy, screenOn: Boolean, generation: Long)
}

/** Saves when the last unlock grace started (FR-037). */
fun interface UnlockGraceStore {
    suspend fun saveUnlockGraceStart(epochMillis: Long)
}
