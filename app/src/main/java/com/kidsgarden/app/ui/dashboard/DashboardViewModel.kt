package com.kidsgarden.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsgarden.app.domain.CallDetectionCapability
import com.kidsgarden.app.domain.DisplayMode
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.ParentFacingSnapshot
import com.kidsgarden.app.domain.SetupStatus
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.domain.VpnCapability
import com.kidsgarden.app.vpn.VpnUnavailableReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** What the dashboard shows. Contains no history and no traffic information. */
data class DashboardUiState(
    val displayMode: DisplayMode,
    val vpnRunning: Boolean,
    val intensity: DullIntensity,
    val setupComplete: Boolean,
    /** Display value only; null without a grant. */
    val remainingPlayMillis: Long?,
    val playPaused: Boolean,
    val showCallLimitation: Boolean,
    /** False while Deactivated (FR-031). */
    val active: Boolean = true,
    /** Android stopped Kids Garden in the background (FR-033). */
    val backgroundStopped: Boolean = false,
    val methods: List<MethodStatus> = emptyList(),
)

/**
 * Parent dashboard (T073). The once-a-second ticker only recomputes the displayed
 * remainder from the last snapshot; it never sends events or changes session state.
 */
class DashboardViewModel(
    snapshot: StateFlow<ParentFacingSnapshot>,
    setup: StateFlow<SetupStatus?>,
    time: TimeProvider,
    /** Current coarse VPN reason; decides whether the stop was a background stop. */
    vpnReason: StateFlow<VpnUnavailableReason?> = MutableStateFlow(null),
) : ViewModel() {

    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(time.elapsedRealtimeMillis())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<DashboardUiState> =
        combine(snapshot, setup, vpnReason, ticks) { s, setupStatus, reason, now -> dashboardState(s, setupStatus, now, reason) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                dashboardState(snapshot.value, setup.value, time.elapsedRealtimeMillis(), vpnReason.value),
            )

    companion object {
        private const val TICK_MS = 1_000L
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Pure projection. While playing, the shown remainder counts down from the
         * snapshot; while paused it stays frozen. It never goes below zero.
         */
        fun dashboardState(
            snapshot: ParentFacingSnapshot,
            setup: SetupStatus?,
            nowElapsed: Long,
            vpnReason: VpnUnavailableReason? = null,
        ): DashboardUiState {
            val remaining = snapshot.remainingPlayMillis?.let { atSnapshot ->
                if (snapshot.playPaused) {
                    atSnapshot
                } else {
                    val passed = (nowElapsed - snapshot.snapshotElapsedMillis).coerceAtLeast(0L)
                    (atSnapshot - passed).coerceAtLeast(0L)
                }
            }
            return DashboardUiState(
                displayMode = snapshot.displayMode,
                vpnRunning = snapshot.vpnCapability == VpnCapability.RUNNING,
                intensity = snapshot.dullIntensity,
                setupComplete = setup?.complete ?: snapshot.setupComplete,
                remainingPlayMillis = remaining,
                playPaused = snapshot.playPaused,
                showCallLimitation = snapshot.callCapability != CallDetectionCapability.AVAILABLE,
                active = snapshot.active,
                backgroundStopped = showBackgroundStop(snapshot, vpnReason),
                methods = if (snapshot.active) methodStatuses(snapshot) else emptyList(),
            )
        }
    }
}
