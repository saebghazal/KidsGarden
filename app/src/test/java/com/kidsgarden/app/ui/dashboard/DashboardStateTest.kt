package com.kidsgarden.app.ui.dashboard

import com.kidsgarden.app.domain.AuthorizationMode
import com.kidsgarden.app.domain.CallDetectionCapability
import com.kidsgarden.app.domain.DisplayMode
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.ParentFacingSnapshot
import com.kidsgarden.app.domain.VpnCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dashboard's display-only projection (T073). */
class DashboardStateTest {

    private fun snapshot(remaining: Long?, paused: Boolean = false, at: Long = 10_000L) = ParentFacingSnapshot(
        displayMode = DisplayMode.CHILD_PLAY_SESSION,
        authorizationMode = AuthorizationMode.CHILD_PLAY_SESSION,
        vpnCapability = VpnCapability.RUNNING,
        callCapability = CallDetectionCapability.AVAILABLE,
        setupComplete = true,
        dullIntensity = DullIntensity.MEDIUM,
        remainingPlayMillis = remaining,
        playPaused = paused,
        snapshotElapsedMillis = at,
        parentAccessActive = true,
        grantPermission = null,
    )

    @Test
    fun countsDownFromTheSnapshotWhilePlaying() {
        val state = DashboardViewModel.dashboardState(snapshot(60_000L), null, nowElapsed = 25_000L)
        assertEquals(45_000L, state.remainingPlayMillis)
    }

    @Test
    fun staysFrozenWhilePaused() {
        val state = DashboardViewModel.dashboardState(snapshot(60_000L, paused = true), null, nowElapsed = 500_000L)
        assertEquals(60_000L, state.remainingPlayMillis)
        assertTrue(state.playPaused)
    }

    @Test
    fun neverNegative() {
        val state = DashboardViewModel.dashboardState(snapshot(5_000L), null, nowElapsed = 1_000_000L)
        assertEquals(0L, state.remainingPlayMillis)
    }

    @Test
    fun clockBeforeSnapshotShowsSnapshotValue() {
        val state = DashboardViewModel.dashboardState(snapshot(60_000L, at = 10_000L), null, nowElapsed = 9_000L)
        assertEquals(60_000L, state.remainingPlayMillis)
    }

    @Test
    fun noGrantShowsNoRemainder() {
        assertEquals(null, DashboardViewModel.dashboardState(snapshot(null), null, nowElapsed = 0L).remainingPlayMillis)
    }

    @Test
    fun callLimitationFollowsDetectionCapability() {
        val unavailable = snapshot(null).copy(callCapability = CallDetectionCapability.PERMISSION_MISSING)
        assertTrue(DashboardViewModel.dashboardState(unavailable, null, 0L).showCallLimitation)
        assertFalse(DashboardViewModel.dashboardState(snapshot(null), null, 0L).showCallLimitation)
    }

    @Test
    fun formatsMinutesAndSecondsRoundingUp() {
        assertEquals("2:05", formatMinutesSeconds(125_000L))
        assertEquals("0:01", formatMinutesSeconds(1L))
        assertEquals("0:00", formatMinutesSeconds(0L))
        assertEquals("30:00", formatMinutesSeconds(30 * 60_000L))
    }
}
