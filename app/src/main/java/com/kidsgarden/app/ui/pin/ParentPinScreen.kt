package com.kidsgarden.app.ui.pin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.ui.dashboard.DeactivatedBanner
import com.kidsgarden.app.ui.setup.SetupScaffold
import kotlinx.coroutines.delay

/** Default entry once a PIN exists. Reports success once through [onAccepted]. */
@Composable
fun ParentPinScreen(viewModel: ParentPinViewModel, time: TimeProvider, onAccepted: () -> Unit, deactivated: Boolean = false) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state == ParentPinViewModel.State.Accepted) onAccepted()
    }
    ParentPinContent(state = state, time = time, onSubmit = viewModel::submit, deactivated = deactivated)
}

/** Masked entry, calm wrong-PIN text, and the retry-delay countdown (display only). */
@Composable
fun ParentPinContent(
    state: ParentPinViewModel.State,
    time: TimeProvider,
    onSubmit: (CharArray) -> Unit,
    /** While Kids Garden is off, say so before anything else (FR-031). */
    deactivated: Boolean = false,
) {
    var pin by remember { mutableStateOf("") }
    var nowElapsed by remember { mutableLongStateOf(time.elapsedRealtimeMillis()) }
    val delayedUntil = (state as? ParentPinViewModel.State.RetryDelayed)?.untilElapsedMillis
    val waitSeconds = delayedUntil?.let { ((it - nowElapsed + 999) / 1000).coerceAtLeast(0) } ?: 0L
    val busy = state == ParentPinViewModel.State.Checking || state == ParentPinViewModel.State.Accepted

    // Ticks only to redraw the countdown; the delay itself is enforced by ParentPinManager.
    LaunchedEffect(delayedUntil) {
        while (delayedUntil != null && time.elapsedRealtimeMillis() < delayedUntil) {
            nowElapsed = time.elapsedRealtimeMillis()
            delay(TICK_MS)
        }
        nowElapsed = time.elapsedRealtimeMillis()
    }

    SetupScaffold(
        title = stringResource(R.string.pin_entry_title),
        actions = {
            Button(
                onClick = {
                    onSubmit(pin.toCharArray())
                    pin = ""
                },
                enabled = !busy && waitSeconds == 0L,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pin_unlock))
            }
        },
    ) {
        if (deactivated) DeactivatedBanner()
        PinField(value = pin, onValueChange = { pin = it }, label = stringResource(R.string.pin_label), enabled = !busy)
        val message: String? = when {
            waitSeconds > 0 -> pluralStringResource(R.plurals.pin_retry_delay, waitSeconds.toInt(), waitSeconds.toInt())
            state == ParentPinViewModel.State.Mismatch -> stringResource(R.string.pin_mismatch)
            state == ParentPinViewModel.State.StorageFailed -> stringResource(R.string.settings_save_failed)
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

private const val TICK_MS = 1_000L
