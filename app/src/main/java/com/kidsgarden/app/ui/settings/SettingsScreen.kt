package com.kidsgarden.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.kidsgarden.app.data.UnlockGraceSettings
import com.kidsgarden.app.domain.UnlockGrace
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.EffectCycle
import com.kidsgarden.app.domain.ScreenEffect
import com.kidsgarden.app.ui.dashboard.DullingMethod
import com.kidsgarden.app.ui.dashboard.MethodState
import com.kidsgarden.app.ui.dashboard.MethodStatus
import androidx.compose.ui.res.pluralStringResource
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
    /** Saved Active/Deactivated choice; changes only after the write succeeds. */
    val active: Boolean = true,
    /** Saved method switches (FR-036). */
    val networkEnabled: Boolean = true,
    val enabledEffects: Set<ScreenEffect> = emptySet(),
    val buildUpSeconds: Int = EffectCycle.DEFAULT_BUILD_UP_SECONDS,
    val breakSeconds: Int = EffectCycle.DEFAULT_BREAK_SECONDS,
    /** Whether each method actually works now. */
    val methods: List<MethodStatus> = emptyList(),
    /** Unlock grace (FR-037). */
    val unlockGrace: UnlockGraceSettings = UnlockGraceSettings(),
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
    onEffectSwitch: (ScreenEffect, Boolean) -> Unit = { _, _ -> },
    onBuildUp: (seconds: Int) -> Unit = {},
    onBreak: (seconds: Int) -> Unit = {},
    onCheckPhone: () -> Unit = {},
    onUnlockGrace: (UnlockGraceSettings) -> Unit = {},
) {
    var lastMethodWarning by remember { mutableStateOf(false) }
    val enabledCount = (if (state.networkEnabled) 1 else 0) + state.enabledEffects.size

    /** The last enabled method cannot be switched off (FR-023); Deactivate is the way off. */
    fun guarded(on: Boolean, change: () -> Unit) {
        if (!on && enabledCount <= 1) {
            lastMethodWarning = true
        } else {
            lastMethodWarning = false
            change()
        }
    }
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

        // The Active/Deactivated switch lives at the top of Parent options; here only the state.
        if (!state.active) BodyText(stringResource(R.string.deactivated_status))

        UnlockGraceSection(state.unlockGrace, onUnlockGrace)

        Heading(stringResource(R.string.settings_intensity_heading))
        RadioGroup(
            options = DullIntensity.entries.map { it to stringResource(intensityName(it)) },
            selected = state.intensity,
            onSelect = onIntensity,
        )

        val rampName = stringResource(R.string.settings_ramp_heading)
        Heading(rampName)
        TimeStepper(rampName, listOf(60, 120), state.softLandingSeconds, onSoftLanding) { minutesLabel(it) }

        Heading(stringResource(R.string.methods_heading))
        // Network dulling = the VPN. Off ends grants and stops it; on goes through VPN setup.
        LabeledSwitch(
            label = stringResource(R.string.method_network),
            checked = state.networkEnabled,
            onCheckedChange = { on -> guarded(on) { if (on) onStartVpn() else onStopVpn() } },
        )
        BodyText(stringResource(if (state.vpnRunning) R.string.dashboard_vpn_running else R.string.vpn_limited))
        for ((effect, label) in listOf(
            ScreenEffect.GREY_SCREEN to R.string.method_grey,
            ScreenEffect.LOWER_VOLUME to R.string.method_volume,
            ScreenEffect.LOWER_BRIGHTNESS to R.string.method_brightness,
        )) {
            LabeledSwitch(
                label = stringResource(label),
                checked = effect in state.enabledEffects,
                onCheckedChange = { on -> guarded(on) { onEffectSwitch(effect, on) } },
            )
        }
        if (lastMethodWarning) {
            Text(
                text = stringResource(R.string.method_last),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        for (status in state.methods) {
            val text = when {
                status.state == MethodState.ACCESS_MISSING && status.method == DullingMethod.GREY -> stringResource(R.string.grey_needs_access)
                status.state == MethodState.ACCESS_MISSING && status.method == DullingMethod.BRIGHTNESS -> stringResource(R.string.brightness_needs_access)
                else -> stringResource(R.string.method_status, stringResource(status.method.label), stringResource(status.state.label))
            }
            BodyText(text)
        }
        OutlinedButton(onClick = onCheckPhone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.compat_check))
        }
        BodyText(stringResource(R.string.settings_vpn_android_note))

        val buildUpName = stringResource(R.string.effect_build_up_heading)
        Heading(buildUpName)
        TimeStepper(buildUpName, EffectCycle.BUILD_UP_SECONDS, state.buildUpSeconds, onBuildUp) { minutesLabel(it) }
        val breakName = stringResource(R.string.effect_break_heading)
        Heading(breakName)
        TimeStepper(breakName, EffectCycle.BREAK_SECONDS, state.breakSeconds, onBreak) {
            if (it == 0) stringResource(R.string.effect_break_none) else minutesLabel(it)
        }

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

/** A full-width row that toggles; TalkBack reads the label with the switch state. */
@Composable
internal fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Unlock grace switch and its three times (FR-037). */
@Composable
private fun UnlockGraceSection(grace: UnlockGraceSettings, onChange: (UnlockGraceSettings) -> Unit) {
    Heading(stringResource(R.string.unlock_grace_heading))
    LabeledSwitch(
        label = stringResource(R.string.unlock_grace_switch),
        checked = grace.enabled,
        onCheckedChange = { onChange(grace.copy(enabled = it)) },
    )
    BodyText(stringResource(R.string.unlock_grace_body))
    if (!grace.enabled) return
    val graceName = stringResource(R.string.unlock_grace_time)
    Text(graceName, style = MaterialTheme.typography.titleSmall)
    TimeStepper(graceName, UnlockGrace.GRACE_SECONDS, grace.graceSeconds, { onChange(grace.copy(graceSeconds = it)) }) { minutesLabel(it) }
    val fadeName = stringResource(R.string.unlock_grace_fade)
    Text(fadeName, style = MaterialTheme.typography.titleSmall)
    TimeStepper(fadeName, UnlockGrace.FADE_SECONDS, grace.fadeSeconds, { onChange(grace.copy(fadeSeconds = it)) }) { minutesLabel(it) }
    val restName = stringResource(R.string.unlock_grace_rest)
    Text(restName, style = MaterialTheme.typography.titleSmall)
    TimeStepper(restName, UnlockGrace.REST_SECONDS, grace.restSeconds, { onChange(grace.copy(restSeconds = it)) }) { durationLabel(it) }
}

@Composable
private fun minutesLabel(seconds: Int): String = pluralStringResource(R.plurals.effect_minutes, seconds / 60, seconds / 60)

@Composable
private fun durationLabel(seconds: Int): String =
    if (seconds % 3600 == 0) pluralStringResource(R.plurals.hours, seconds / 3600, seconds / 3600) else minutesLabel(seconds)
