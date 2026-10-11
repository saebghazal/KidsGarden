package com.kidsgarden.app.ui

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.AuthorizationMode
import com.kidsgarden.app.domain.BoundaryScheduler
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.domain.DomainSettings
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.NetworkPolicy
import com.kidsgarden.app.domain.NetworkPolicySink
import com.kidsgarden.app.domain.SessionManager
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.domain.VpnCapability
import com.kidsgarden.app.vpn.StatusNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Quiet expiry (T072): the ramp and expiry change only network policy. No activity is
 * launched, nothing alerts, and the foreground notification text stays the same.
 */
@RunWith(AndroidJUnit4::class)
class SoftLandingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var elapsed = 1_000_000L
    private val time = object : TimeProvider {
        override fun elapsedRealtimeMillis() = elapsed
        override fun epochMillis() = elapsed
    }
    private val policies = CopyOnWriteArrayList<NetworkPolicy>()
    private val boundaries = CopyOnWriteArrayList<Pair<Long, Long>>()

    @After
    fun tearDown() = scope.cancel()

    private fun session(): SessionManager = SessionManager(
        time = time,
        policySink = object : NetworkPolicySink {
            override suspend fun apply(policy: NetworkPolicy, generation: Long, validUntilElapsedMillis: Long?) {
                policies += policy
            }
        },
        boundaryScheduler = object : BoundaryScheduler {
            override fun schedule(atElapsedMillis: Long, generation: Long) {
                boundaries += atElapsedMillis to generation
            }
            override fun cancel() = Unit
        },
        scope = scope,
    )

    private fun resumedActivityCount(): Int {
        var count = 0
        instrumentation.runOnMainSync {
            count = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).size
        }
        return count
    }

    private fun title(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test
    fun rampAndExpiryAreQuiet() = runBlocking {
        val session = session()
        val accessCleared = AtomicInteger(0)
        session.dispatch(DomainEvent.SettingsChanged(DomainSettings(credentialPresent = true, onboardingComplete = true)))
        session.dispatch(DomainEvent.InteractivityObserved(true))
        session.dispatch(DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
        session.dispatch(DomainEvent.PinVerified)
        val permission = withTimeout(5_000) { session.state.first { it.grantPermission != null } }.grantPermission!!
        session.dispatch(DomainEvent.StartChildRequested(permission, 10))
        withTimeout(5_000) { session.state.first { it.authorizationMode == AuthorizationMode.CHILD_PLAY_SESSION } }

        val before = StatusNotification.build(context, null)
        val activitiesBefore = resumedActivityCount()
        scope.launch { session.parentUiAccessCleared.collect { accessCleared.incrementAndGet() } }

        // Walk every boundary through the ramp to expiry.
        while (session.state.value.authorizationMode != AuthorizationMode.DULL) {
            val (at, generation) = boundaries.last()
            elapsed = at
            session.dispatch(DomainEvent.DeadlineObserved(generation))
            withTimeout(5_000) { session.state.first { it.authorizationMode == AuthorizationMode.DULL || boundaries.last().first > at } }
        }

        assertEquals(NetworkPolicy.Dull(DullIntensity.MEDIUM), policies.last())
        assertNull(session.state.value.remainingPlayMillis)
        assertEquals(0, accessCleared.get())
        assertEquals(activitiesBefore, resumedActivityCount())

        val after = StatusNotification.build(context, null)
        assertEquals(context.getString(R.string.notification_running), title(after))
        assertEquals(title(before), title(after))
    }

    @Test
    fun statusChannelNeverAlerts() {
        val channel = StatusNotification.channel(context)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertTrue(channel.importance <= NotificationManager.IMPORTANCE_LOW)
    }
}
