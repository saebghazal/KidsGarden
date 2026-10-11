package com.kidsgarden.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsgarden.app.R
import com.kidsgarden.app.ui.pin.PinField

/** Stateful entry point: wires [CreatePinViewModel] and reports success once. */
@Composable
fun CreatePinScreen(viewModel: CreatePinViewModel, onCreated: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state == CreatePinViewModel.State.Created) onCreated()
    }
    CreatePinContent(state = state, onSubmit = viewModel::submit)
}

/**
 * Six-digit masked entry plus confirmation (T035). Text lives in plain `remember`, so it
 * is cleared on recreation and never saved.
 */
@Composable
fun CreatePinContent(
    state: CreatePinViewModel.State,
    onSubmit: (CharArray, CharArray) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val saving = state == CreatePinViewModel.State.Saving || state == CreatePinViewModel.State.Created

    SetupScaffold(
        title = stringResource(R.string.create_pin_title),
        actions = {
            Button(
                onClick = {
                    onSubmit(pin.toCharArray(), confirmation.toCharArray())
                    // Nothing lingers on screen after a submit, whatever the outcome.
                    pin = ""
                    confirmation = ""
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pin_save))
            }
        },
    ) {
        BodyText(stringResource(R.string.create_pin_body))
        PinField(value = pin, onValueChange = { pin = it }, label = stringResource(R.string.pin_label), enabled = !saving)
        PinField(
            value = confirmation,
            onValueChange = { confirmation = it },
            label = stringResource(R.string.pin_confirm_label),
            enabled = !saving,
        )
        val message = when (state) {
            CreatePinViewModel.State.InvalidFormat -> R.string.pin_invalid_format
            CreatePinViewModel.State.ConfirmationMismatch -> R.string.pin_confirmation_mismatch
            CreatePinViewModel.State.StorageFailed -> R.string.settings_save_failed
            CreatePinViewModel.State.AlreadyCreated -> R.string.pin_already_created
            else -> null
        }
        if (message != null) {
            Text(
                text = stringResource(message),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
