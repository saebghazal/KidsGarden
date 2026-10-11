package com.kidsgarden.app.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.kidsgarden.app.domain.CallDetectionCapability
import com.kidsgarden.app.domain.CallObservation
import com.kidsgarden.app.domain.CallSourceState
import com.kidsgarden.app.domain.CallStateSource
import com.kidsgarden.app.domain.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Normal mobile/Telecom call detection (T061, contracts/call-state-monitor.md).
 *
 * One `TelephonyCallback.CallStateListener` per active subscription, created with
 * `createForSubscriptionId`, re-registered whenever subscriptions change. OFFHOOK on any
 * subscription is ACTIVE; idle or ringing everywhere is INACTIVE; missing permission or any
 * failure is UNAVAILABLE. Never uses `TelecomManager.isInCall`, the device-wide
 * `getCallState`, or `AudioManager`, all of which would also see app calls.
 *
 * Observations carry no number, contact, SIM, or subscription identity. All registration
 * work runs on the main thread.
 */
class TelephonyCallStateMonitor(
    context: Context,
    private val time: TimeProvider,
) : CallStateSource {

    private val appContext = context.applicationContext
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)
    private val subscriptions = appContext.getSystemService(SubscriptionManager::class.java)
    private val executor = appContext.mainExecutor
    private val aggregator = TelephonyStateAggregator()

    private val _observations = MutableStateFlow(observation(CallSourceState.UNAVAILABLE))
    override val observations: StateFlow<CallObservation> = _observations.asStateFlow()

    private val _capability = MutableStateFlow(CallDetectionCapability.PERMISSION_MISSING)
    val capability: StateFlow<CallDetectionCapability> = _capability.asStateFlow()

    private val registered = mutableListOf<Pair<TelephonyManager, TelephonyCallback>>()
    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? = null

    /** Starts or restarts monitoring with a new generation. Call on the main thread. */
    fun start() {
        unregisterCallbacks()
        if (!permissionGranted()) {
            aggregator.restart(permission = false)
            _capability.value = CallDetectionCapability.PERMISSION_MISSING
            publish()
            removeSubscriptionListener()
            return
        }
        val generation = aggregator.restart(permission = true)
        try {
            ensureSubscriptionListener()
            registerCallbacks(generation)
            _capability.value = CallDetectionCapability.AVAILABLE
        } catch (_: SecurityException) {
            fail(CallDetectionCapability.PERMISSION_MISSING)
            return
        } catch (_: IllegalStateException) {
            fail(CallDetectionCapability.UNAVAILABLE)
            return
        }
        publish()
    }

    /** Re-checks permission (for example after the prompt or on resume) and restarts if it changed. */
    fun refresh() {
        val granted = permissionGranted()
        val running = _capability.value == CallDetectionCapability.AVAILABLE
        if (granted != running) start()
    }

    fun stop() {
        unregisterCallbacks()
        removeSubscriptionListener()
        aggregator.permissionLost()
        publish()
    }

    /**
     * Fresh per-subscription query; never answers from cached callbacks. Any failure is
     * UNAVAILABLE, which resumes countdown.
     */
    override suspend fun reconcile(): CallObservation = withContext(Dispatchers.IO) {
        val generation = withContext(Dispatchers.Main) { aggregator.generation }
        if (!permissionGranted()) return@withContext CallObservation(CallSourceState.UNAVAILABLE, generation, time.elapsedRealtimeMillis())
        val state = try {
            val fresh = activeSubscriptionIds().map { id ->
                TelephonyStateAggregator.fromTelephony(telephony.createForSubscriptionId(id).callStateForSubscription)
            }
            TelephonyStateAggregator.aggregate(permission = true, states = fresh)
        } catch (_: SecurityException) {
            CallSourceState.UNAVAILABLE
        } catch (_: IllegalStateException) {
            CallSourceState.UNAVAILABLE
        }
        CallObservation(state, generation, time.elapsedRealtimeMillis())
    }

    private fun registerCallbacks(generation: Long) {
        for (id in activeSubscriptionIds()) {
            val manager = telephony.createForSubscriptionId(id)
            val callback = SubscriptionCallback(key = id, generation = generation)
            manager.registerTelephonyCallback(executor, callback)
            registered += manager to callback
        }
    }

    private fun unregisterCallbacks() {
        for ((manager, callback) in registered) {
            try {
                manager.unregisterTelephonyCallback(callback)
            } catch (_: IllegalArgumentException) {
                // already gone
            }
        }
        registered.clear()
        aggregator.clear()
    }

    private fun ensureSubscriptionListener() {
        if (subscriptionListener != null) return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            // New, removed, or swapped SIMs: re-register everything under a new generation.
            override fun onSubscriptionsChanged() = start()
        }
        subscriptions.addOnSubscriptionsChangedListener(executor, listener)
        subscriptionListener = listener
    }

    private fun removeSubscriptionListener() {
        subscriptionListener?.let(subscriptions::removeOnSubscriptionsChangedListener)
        subscriptionListener = null
    }

    // Both callers (start, reconcile) check READ_PHONE_STATE first and catch the
    // SecurityException thrown if it is revoked in between.
    @SuppressLint("MissingPermission")
    private fun activeSubscriptionIds(): List<Int> =
        subscriptions.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }

    private fun fail(capability: CallDetectionCapability) {
        unregisterCallbacks()
        aggregator.permissionLost()
        _capability.value = capability
        publish()
    }

    private fun publish() {
        _observations.value = observation(aggregator.aggregate())
    }

    private fun observation(state: CallSourceState) =
        CallObservation(state, aggregator.generation, time.elapsedRealtimeMillis())

    private fun permissionGranted(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private inner class SubscriptionCallback(
        private val key: Int,
        private val generation: Long,
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (!permissionGranted()) return fail(CallDetectionCapability.PERMISSION_MISSING)
            if (aggregator.update(key, generation, TelephonyStateAggregator.fromTelephony(state))) publish()
        }
    }
}
