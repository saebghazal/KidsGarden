package com.kidsgarden.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R

/**
 * Parent explanation before the READ_PHONE_STATE prompt (T062). Skipping is always
 * possible; without the permission the screen shows the call limitation, and setup,
 * sessions, and dulling still work.
 *
 * [allowed] is the live permission state; null means the parent has not answered yet.
 */
@Composable
fun PhoneStatePermissionScreen(
    allowed: Boolean?,
    onAllow: () -> Unit,
    onContinue: () -> Unit,
    saveFailed: Boolean = false,
) {
    SetupScaffold(
        title = stringResource(R.string.phone_title),
        actions = {
            if (allowed == true) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_continue))
                }
            } else {
                Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.phone_allow))
                }
                OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (allowed == false) R.string.action_continue else R.string.phone_skip))
                }
            }
        },
    ) {
        BodyText(stringResource(R.string.phone_body))
        when (allowed) {
            true -> BodyText(stringResource(R.string.phone_allowed))
            false -> BodyText(stringResource(R.string.call_limitation))
            null -> Unit
        }
        if (saveFailed) BodyText(stringResource(R.string.settings_save_failed))
    }
}
