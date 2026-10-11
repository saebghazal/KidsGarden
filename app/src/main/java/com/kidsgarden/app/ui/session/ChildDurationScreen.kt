package com.kidsgarden.app.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.StateMachine
import com.kidsgarden.app.ui.setup.SetupScaffold

/**
 * Duration choice for "Give phone to child" (T063): 10, 20, or 30 minutes only. Leaving
 * the screen without choosing starts nothing.
 */
@Composable
fun ChildDurationScreen(
    state: ChildSessionViewModel.State,
    onStart: (minutes: Int) -> Unit,
) {
    val busy = state == ChildSessionViewModel.State.Starting || state == ChildSessionViewModel.State.Started
    SetupScaffold(
        title = stringResource(R.string.child_duration_title),
        actions = {
            for (minutes in StateMachine.CHILD_DURATIONS_MINUTES.sorted()) {
                Button(onClick = { onStart(minutes) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(pluralStringResource(R.plurals.child_duration_minutes, minutes, minutes))
                }
            }
        },
    ) {
        if (state == ChildSessionViewModel.State.Failed) {
            Text(
                text = stringResource(R.string.child_start_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Brief, inert confirmation after the handoff: no buttons, no countdown, no parent
 * controls. Navigation replaces it with the neutral PIN entry.
 */
@Composable
fun ChildStartedScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.child_started), style = MaterialTheme.typography.headlineSmall)
    }
}
