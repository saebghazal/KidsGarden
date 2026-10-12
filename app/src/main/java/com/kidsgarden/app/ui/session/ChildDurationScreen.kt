package com.kidsgarden.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.StateMachine
import com.kidsgarden.app.ui.setup.SetupScaffold

/**
 * Duration choice for "Give phone to child" (T063): up and down arrows move through
 * [StateMachine.CHILD_DURATIONS_MINUTES] in 10-minute steps, starting at the shortest.
 * Nothing starts until the parent taps Start; leaving the screen starts nothing.
 */
@Composable
fun ChildDurationScreen(
    state: ChildSessionViewModel.State,
    onStart: (minutes: Int) -> Unit,
) {
    val durations = StateMachine.CHILD_DURATIONS_MINUTES
    val shortest = durations.min()
    val longest = durations.max()
    val step = StateMachine.CHILD_DURATION_STEP_MINUTES
    var minutes by rememberSaveable { mutableIntStateOf(shortest) }
    val busy = state == ChildSessionViewModel.State.Starting || state == ChildSessionViewModel.State.Started
    val decrease = pluralStringResource(R.plurals.child_duration_decrease, step, step)
    val increase = pluralStringResource(R.plurals.child_duration_increase, step, step)
    SetupScaffold(
        title = stringResource(R.string.child_duration_title),
        actions = {
            Button(onClick = { onStart(minutes) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.child_duration_start))
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedIconButton(
                onClick = { minutes = (minutes - step).coerceAtLeast(shortest) },
                enabled = !busy && minutes > shortest,
                modifier = Modifier.size(56.dp).semantics { contentDescription = decrease },
            ) {
                Text("▼", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = pluralStringResource(R.plurals.child_duration_minutes, minutes, minutes),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedIconButton(
                onClick = { minutes = (minutes + step).coerceAtMost(longest) },
                enabled = !busy && minutes < longest,
                modifier = Modifier.size(56.dp).semantics { contentDescription = increase },
            ) {
                Text("▲", style = MaterialTheme.typography.titleLarge)
            }
        }
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
