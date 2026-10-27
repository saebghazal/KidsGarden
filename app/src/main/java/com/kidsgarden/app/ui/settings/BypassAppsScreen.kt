package com.kidsgarden.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kidsgarden.app.R
import com.kidsgarden.app.ui.setup.BodyText
import com.kidsgarden.app.ui.setup.SetupScaffold

/** One app the parent can let skip Kids Garden. Only shown on this parent screen; never stored except when chosen. */
data class LaunchableApp(val packageName: String, val label: String)

/**
 * Apps that skip Kids Garden (FR-038, clarification 2026-10-27). Parent-only, behind the PIN.
 * A checked app bypasses the VPN entirely: it is never slowed, in Dull Mode or otherwise.
 * There is deliberately no "turn the VPN off" control here. [apps] is null while loading.
 */
@Composable
fun BypassAppsScreen(
    apps: List<LaunchableApp>?,
    selected: Set<String>,
    saveFailed: Boolean,
    onToggle: (packageName: String, skip: Boolean) -> Unit,
    onDone: () -> Unit,
) {
    SetupScaffold(
        title = stringResource(R.string.bypass_title),
        actions = {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_finish))
            }
        },
    ) {
        BodyText(stringResource(R.string.bypass_body))
        if (saveFailed) BodyText(stringResource(R.string.settings_save_failed))
        if (apps == null) BodyText(stringResource(R.string.bypass_loading))
        for (app in apps.orEmpty()) {
            val checked = app.packageName in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(app.packageName, it) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(text = app.label, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/**
 * Apps with a launcher icon, read when the parent opens the screen. Visibility comes from the
 * manifest `<queries>` launcher intent; QUERY_ALL_PACKAGES is not used. Kids Garden itself is
 * left out (its own traffic must stay inside its VPN).
 */
fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { (pkg, _) -> pkg != context.packageName }
        .distinctBy { it.first }
        .map { (pkg, label) -> LaunchableApp(pkg, label) }
        .sortedBy { it.label.lowercase() }
}
