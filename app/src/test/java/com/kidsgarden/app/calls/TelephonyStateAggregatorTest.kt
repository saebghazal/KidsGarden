package com.kidsgarden.app.calls

import com.kidsgarden.app.domain.CallSourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyStateAggregatorTest {

    private val aggregator = TelephonyStateAggregator()

    @Test
    fun mapsTelephonyCallStates() {
        assertEquals(SubscriptionCallState.IDLE, TelephonyStateAggregator.fromTelephony(0))
        assertEquals(SubscriptionCallState.RINGING, TelephonyStateAggregator.fromTelephony(1))
        assertEquals(SubscriptionCallState.OFFHOOK, TelephonyStateAggregator.fromTelephony(2))
        assertEquals(SubscriptionCallState.IDLE, TelephonyStateAggregator.fromTelephony(99))
    }

    @Test
    fun offhookOnAnySubscriptionIsActive() {
        val generation = aggregator.restart(permission = true)
        aggregator.update(key = 1, callbackGeneration = generation, state = SubscriptionCallState.IDLE)
        aggregator.update(key = 2, callbackGeneration = generation, state = SubscriptionCallState.OFFHOOK)
        assertEquals(CallSourceState.ACTIVE, aggregator.aggregate())
    }

    @Test
    fun idleOrRingingEverywhereIsInactive() {
        val generation = aggregator.restart(permission = true)
        aggregator.update(1, generation, SubscriptionCallState.RINGING)
        aggregator.update(2, generation, SubscriptionCallState.IDLE)
        assertEquals(CallSourceState.INACTIVE, aggregator.aggregate())
    }

    @Test
    fun noSubscriptionsIsInactive() {
        aggregator.restart(permission = true)
        assertEquals(CallSourceState.INACTIVE, aggregator.aggregate())
    }

    @Test
    fun removingTheActiveSubscriptionEndsActive() {
        val generation = aggregator.restart(permission = true)
        aggregator.update(1, generation, SubscriptionCallState.OFFHOOK)
        aggregator.remove(1)
        assertEquals(CallSourceState.INACTIVE, aggregator.aggregate())
    }

    @Test
    fun permissionMissingOrLostIsUnavailable() {
        aggregator.restart(permission = false)
        assertEquals(CallSourceState.UNAVAILABLE, aggregator.aggregate())

        val generation = aggregator.restart(permission = true)
        aggregator.update(1, generation, SubscriptionCallState.OFFHOOK)
        aggregator.permissionLost()
        assertEquals(CallSourceState.UNAVAILABLE, aggregator.aggregate())
    }

    @Test
    fun staleGenerationCallbacksAreIgnored() {
        val old = aggregator.restart(permission = true)
        val current = aggregator.restart(permission = true)
        assertTrue(current > old)
        assertFalse(aggregator.update(1, old, SubscriptionCallState.OFFHOOK))
        assertEquals(CallSourceState.INACTIVE, aggregator.aggregate())
    }

    @Test
    fun restartDropsCachedActive() {
        val generation = aggregator.restart(permission = true)
        aggregator.update(1, generation, SubscriptionCallState.OFFHOOK)
        aggregator.restart(permission = true)
        assertEquals(CallSourceState.INACTIVE, aggregator.aggregate())
    }

    @Test
    fun apiAcceptsOnlyTelephonyInputs() {
        // The aggregator's public inputs are subscription call states, permission, and
        // generation. There is no parameter for app calls, audio mode, or notifications.
        val publicInputs = TelephonyStateAggregator::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .flatMap { it.parameterTypes.toList() }
            .map { it.simpleName }
            .toSet()
        val allowed = setOf("int", "long", "boolean", "SubscriptionCallState", "Collection")
        assertTrue("unexpected inputs: ${publicInputs - allowed}", (publicInputs - allowed).isEmpty())
    }
}
