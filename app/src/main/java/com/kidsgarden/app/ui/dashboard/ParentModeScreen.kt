package com.kidsgarden.app.ui.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R
import com.kidsgarden.app.ui.setup.BodyText
import com.kidsgarden.app.ui.setup.SetupScaffold

/**
 * Parent-only status while Parent Mode is on (T055). No timer: Parent Mode lasts until the
 * screen turns off, the phone locks, or the parent exits here (no PIN needed to exit).
 */
@Composable
fun ParentModeScreen(onExit: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.parent_mode_title),
        actions = {
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.parent_mode_exit))
            }
        },
    ) {
        BodyText(stringResource(R.string.parent_mode_confirmation))
    }
}
