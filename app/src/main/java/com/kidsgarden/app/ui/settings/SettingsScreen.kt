package com.kidsgarden.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.ui.dashboard.intensityName
import com.kidsgarden.app.ui.setup.BodyText
import com.kidsgarden.app.ui.setup.SetupScaffold

/** What Settings shows. VPN status is live capability, never the saved preference. */
data class SettingsUiState(
    val intensity: DullIntensity,
    val softLandingSeconds: Int,
    val vpnRunning: Boolean,
    val callPausePermissionMissing: Boolean,
    val saveFailed: Boolean,
)

/**
 * Parent settings (T074). Changing intensity or ramp length affects DULL right away and
 * future sessions only; a running session keeps what it captured.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntensity: (DullIntensity) -> Unit,
    onSoftLanding: (seconds: Int) -> Unit,
    onStopVpn: () -> Unit,
    onStartVpn: () -> Unit,
    onChangePin: () -> Unit,
    onLimitations: () -> Unit,
    onPrivacy: () -> Unit,
    onEnableCallPause: () -> Unit,
) {
    SetupScaffold(
        title = stringResource(R.string.settings_title),
        actions = {
            OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_change_pin))
            }
            OutlinedButton(onClick = onLimitations, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_limitations))
            }
            OutlinedButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_privacy))
            }
        },
    ) {
        if (state.saveFailed) {
            Text(
                text = stringResource(R.string.settings_save_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Heading(stringResource(R.string.settings_intensity_heading))
        RadioGroup(
            options = DullIntensity.entries.map { it to stringResource(intensityName(it)) },
            selected = state.intensity,
            onSelect = onIntensity,
        )

        Heading(stringResource(R.string.settings_ramp_heading))
        RadioGroup(
            options = listOf(60 to stringResource(R.string.ramp_one_minute), 120 to stringResource(R.string.ramp_two_minutes)),
            selected = state.softLandingSeconds,
            onSelect = onSoftLanding,
        )

        Heading(stringResource(R.string.settings_vpn_heading))
        BodyText(stringResource(if (state.vpnRunning) R.string.dashboard_vpn_running else R.string.vpn_limited))
        OutlinedButton(onClick = if (state.vpnRunning) onStopVpn else onStartVpn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (state.vpnRunning) R.string.settings_vpn_stop else R.string.settings_vpn_start))
        }
        BodyText(stringResource(R.string.settings_vpn_android_note))

        if (state.callPausePermissionMissing) {
            BodyText(stringResource(R.string.call_limitation))
            OutlinedButton(onClick = onEnableCallPause, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_enable_call_pause))
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun <T> RadioGroup(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for ((value, label) in options) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(text = label, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
