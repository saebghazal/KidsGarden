package com.kidsgarden.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private class RecordingSink : NetworkPolicySink {
        val applied = mutableListOf<Pair<NetworkPolicy, Long>>()
        override suspend fun apply(policy: NetworkPolicy, generation: Long) {
            applied += policy to generation
        }
    }

    private class RecordingScheduler : BoundaryScheduler {
        var cancels = 0
        override fun schedule(atElapsedMillis: Long, generation: Long) = Unit
        override fun cancel() {
            cancels++
        }
    }

    /**
     * Eager scope tied to the test's background scope: events are processed immediately
     * on dispatch and the loop is cancelled when the test ends.
     */
    private fun TestScope.eagerScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.manager(sink: NetworkPolicySink, scheduler: BoundaryScheduler) =
        SessionManager(FakeTimeProvider(), sink, scheduler, eagerScope())

    @Test
    fun processStartAppliesPolicyAndPublishesDull() = runTest {
        val sink = RecordingSink()
        val scheduler = RecordingScheduler()
        val manager = manager(sink, scheduler)

        manager.dispatch(DomainEvent.ProcessStarted)

        assertEquals(AuthorizationMode.DULL, manager.state.value.authorizationMode)
        assertTrue(sink.applied.single().first is NetworkPolicy.Unavailable)
        assertEquals(1, scheduler.cancels)
        assertFalse(manager.state.value.parentAccessActive)
    }

    @Test
    fun pinThenUiLeftOpensAndClosesParentAccess() = runTest {
        val manager = manager(RecordingSink(), RecordingScheduler())

        manager.dispatch(DomainEvent.PinVerified)
        assertTrue(manager.state.value.parentAccessActive)
        assertEquals(AuthorizationMode.DULL, manager.state.value.authorizationMode)

        manager.dispatch(DomainEvent.ParentUiLeft)
        assertFalse(manager.state.value.parentAccessActive)
    }

    @Test
    fun clearingParentAccessIsAnnounced() = runTest {
        val manager = manager(RecordingSink(), RecordingScheduler())
        val cleared = mutableListOf<Unit>()
        eagerScope().launch { manager.parentUiAccessCleared.collect { cleared += it } }

        manager.dispatch(DomainEvent.PinVerified)
        manager.dispatch(DomainEvent.ParentUiLeft)

        assertEquals(1, cleared.size)
    }
}
