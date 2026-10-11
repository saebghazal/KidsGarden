package com.kidsgarden.app.ui.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.DisplayMode
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.ui.setup.BodyText
import com.kidsgarden.app.ui.setup.SetupScaffold

/** Parent-only status (T073): real mode, real VPN status, intensity, setup, remaining play. */
@Composable
fun DashboardScreen(state: DashboardUiState, onParentOptions: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.dashboard_title),
        actions = {
            OutlinedButton(onClick = onParentOptions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.actions_title))
            }
        },
    ) {
        BodyText(stringResource(modeText(state.displayMode)))
        state.remainingPlayMillis?.let { remaining ->
            BodyText(
                if (state.playPaused) stringResource(R.string.dashboard_paused)
                else stringResource(R.string.dashboard_remaining, formatMinutesSeconds(remaining)),
            )
        }
        BodyText(stringResource(if (state.vpnRunning) R.string.dashboard_vpn_running else R.string.vpn_limited))
        BodyText(stringResource(R.string.dashboard_intensity, stringResource(intensityName(state.intensity))))
        BodyText(stringResource(if (state.setupComplete) R.string.dashboard_setup_complete else R.string.dashboard_setup_incomplete))
        if (state.showCallLimitation) BodyText(stringResource(R.string.call_limitation))
    }
}

internal fun modeText(mode: DisplayMode): Int = when (mode) {
    DisplayMode.DULL -> R.string.mode_dull
    DisplayMode.SETUP_REQUIRED -> R.string.vpn_limited
    DisplayMode.PARENT_MODE -> R.string.mode_parent
    DisplayMode.CHILD_PLAY_SESSION -> R.string.mode_child
    DisplayMode.SOFT_LANDING -> R.string.mode_soft_landing
}

internal fun intensityName(intensity: DullIntensity): Int = when (intensity) {
    DullIntensity.GENTLE -> R.string.intensity_gentle
    DullIntensity.MEDIUM -> R.string.intensity_medium
    DullIntensity.STRONG -> R.string.intensity_strong
}

/** m:ss, rounded up so the last second shows as 0:01 rather than 0:00. */
internal fun formatMinutesSeconds(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
    return "%d:%02d".format(java.util.Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
}
