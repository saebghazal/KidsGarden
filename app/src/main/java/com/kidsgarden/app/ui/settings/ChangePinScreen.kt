package com.kidsgarden.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.ui.pin.PinField
import com.kidsgarden.app.ui.setup.SetupScaffold

/**
 * Current PIN, new PIN, and confirmation (T075). Text lives in plain `remember`, so it is
 * cleared on recreation and never saved; fields clear after every submit.
 */
@Composable
fun ChangePinScreen(
    state: ChangePinViewModel.State,
    time: TimeProvider,
    onSubmit: (current: CharArray, newPin: CharArray, confirmation: CharArray) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val busy = state == ChangePinViewModel.State.Saving || state == ChangePinViewModel.State.Changed

    SetupScaffold(
        title = stringResource(R.string.change_pin_title),
        actions = {
            Button(
                onClick = {
                    onSubmit(current.toCharArray(), newPin.toCharArray(), confirmation.toCharArray())
                    current = ""
                    newPin = ""
                    confirmation = ""
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.change_pin_save))
            }
        },
    ) {
        PinField(value = current, onValueChange = { current = it }, label = stringResource(R.string.pin_current_label), enabled = !busy)
        PinField(value = newPin, onValueChange = { newPin = it }, label = stringResource(R.string.pin_new_label), enabled = !busy)
        PinField(
            value = confirmation,
            onValueChange = { confirmation = it },
            label = stringResource(R.string.pin_confirm_label),
            enabled = !busy,
        )
        val message: String? = when (state) {
            ChangePinViewModel.State.CurrentMismatch -> stringResource(R.string.pin_mismatch)
            ChangePinViewModel.State.InvalidNewPin -> stringResource(R.string.pin_invalid_format)
            ChangePinViewModel.State.NewPinMismatch -> stringResource(R.string.pin_confirmation_mismatch)
            ChangePinViewModel.State.StorageFailed -> stringResource(R.string.settings_save_failed)
            is ChangePinViewModel.State.RetryDelayed -> {
                val seconds = ((state.untilElapsedMillis - time.elapsedRealtimeMillis() + 999) / 1000).coerceAtLeast(1).toInt()
                pluralStringResource(R.plurals.pin_retry_delay, seconds, seconds)
            }
            else -> null
        }
        if (message != null) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
