package com.kidsgarden.app.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kidsgarden.app.R

/**
 * The Active/Deactivated switch at the top of Parent options (FR-031, owner change
 * 2026-10-19). The parent already entered the PIN to get here, so switching takes effect at
 * once in both directions: no second PIN and no pop-up. While off, the whole card turns
 * the warning colour so it is obvious that the phone is not dulled.
 */
@Composable
fun ActiveSwitchCard(active: Boolean, onActiveChange: (Boolean) -> Unit, failed: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (active) colors.secondaryContainer else colors.errorContainer,
        contentColor = if (active) colors.onSecondaryContainer else colors.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = active,
                        role = Role.Switch,
                        onValueChange = onActiveChange,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.active_switch_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = active, onCheckedChange = null)
            }
            Text(
                text = stringResource(if (active) R.string.active_status else R.string.deactivated_status),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) null else FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (failed) {
                Text(
                    text = stringResource(R.string.settings_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Shown on the PIN screen while Deactivated, so anyone can see the phone is not dulled. */
@Composable
fun DeactivatedBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.deactivated_status),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
    }
}
