package com.kidsgarden.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kidsgarden.app.R

/**
 * A time setting chosen with down and up arrows (owner change 2026-10-25), stepping through
 * the allowed [values] in order, like the child duration picker. [name] labels the arrows
 * for TalkBack. A value outside [values] shows the nearest allowed one.
 */
@Composable
internal fun TimeStepper(
    name: String,
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    label: @Composable (Int) -> String,
) {
    val index = values.indexOf(selected).takeIf { it >= 0 }
        ?: values.indices.minBy { kotlin.math.abs(values[it] - selected) }
    val less = stringResource(R.string.stepper_decrease, name)
    val more = stringResource(R.string.stepper_increase, name)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedIconButton(
            onClick = { onSelect(values[index - 1]) },
            enabled = index > 0,
            modifier = Modifier.size(48.dp).semantics { contentDescription = less },
        ) {
            Text("▼", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = label(values[index]),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedIconButton(
            onClick = { onSelect(values[index + 1]) },
            enabled = index < values.lastIndex,
            modifier = Modifier.size(48.dp).semantics { contentDescription = more },
        ) {
            Text("▲", style = MaterialTheme.typography.titleMedium)
        }
    }
}
