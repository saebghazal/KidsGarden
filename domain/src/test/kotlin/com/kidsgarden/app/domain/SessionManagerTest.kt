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
        override suspend fun apply(policy: NetworkPolicy, generation: Long, validUntilElapsedMillis: Long?) {
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

        manager.dispatch(DomainEvent.ProcessStarted())

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

    private class RecordingVpn : VpnLifecycle {
        var starts = 0
        var stops = 0
        override fun startVpn() {
            starts++
        }
        override fun stopVpn() {
            stops++
        }
    }

    @Test
    fun deactivateSavesThenStopsVpn() = runTest {
        val saved = mutableListOf<Boolean>()
        val vpn = RecordingVpn()
        val manager = SessionManager(
            FakeTimeProvider(), RecordingSink(), RecordingScheduler(), eagerScope(),
            activationStore = { saved += it; true },
            vpnLifecycle = vpn,
        )
        manager.dispatch(DomainEvent.PinVerified)

        val result = manager.deactivate(confirmed = true)

        assertTrue(result.isSuccess)
        assertEquals(listOf(false), saved)
        assertEquals(1, vpn.stops)
        assertEquals(DisplayMode.DEACTIVATED, manager.state.value.displayMode)
        assertFalse(manager.state.value.active)
    }

    @Test
    fun failedDeactivateSaveReportsStorageAndKeepsState() = runTest {
        val vpn = RecordingVpn()
        val manager = SessionManager(
            FakeTimeProvider(), RecordingSink(), RecordingScheduler(), eagerScope(),
            activationStore = { false },
            vpnLifecycle = vpn,
        )
        manager.dispatch(DomainEvent.PinVerified)

        val result = manager.deactivate(confirmed = true)

        assertTrue(result.exceptionOrNull() is SessionError.StorageUnavailable)
        assertEquals(0, vpn.stops)
        assertTrue(manager.state.value.active)
    }

    @Test
    fun rejectedRequestsReturnStale() = runTest {
        val manager = SessionManager(
            FakeTimeProvider(), RecordingSink(), RecordingScheduler(), eagerScope(),
            activationStore = { true },
        )
        assertTrue(manager.deactivate(confirmed = true).exceptionOrNull() is SessionError.StaleRequest)
        assertTrue(manager.activate(grantPermission = 1L).exceptionOrNull() is SessionError.StaleRequest)
    }

    @Test
    fun activateWithFreshPermissionStartsVpn() = runTest {
        val vpn = RecordingVpn()
        val manager = SessionManager(
            FakeTimeProvider(), RecordingSink(), RecordingScheduler(), eagerScope(),
            activationStore = { true },
            vpnLifecycle = vpn,
        )
        manager.dispatch(DomainEvent.ProcessStarted(savedActive = false))
        manager.dispatch(DomainEvent.PinVerified)
        val permission = manager.state.value.grantPermission!!

        assertTrue(manager.activate(permission).isSuccess)
        assertEquals(1, vpn.starts)
        assertEquals(AuthorizationMode.DULL, manager.state.value.authorizationMode)
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
