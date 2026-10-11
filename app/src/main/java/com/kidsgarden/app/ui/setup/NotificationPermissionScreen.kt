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
 * Parent explanation shown before the POST_NOTIFICATIONS prompt (T037). Continuing is
 * always possible: denial is a limitation, not a setup blocker.
 *
 * [allowed] is the live permission state; null means the parent has not answered yet.
 */
@Composable
fun NotificationPermissionScreen(
    allowed: Boolean?,
    onAllow: () -> Unit,
    onContinue: () -> Unit,
) {
    SetupScaffold(
        title = stringResource(R.string.notifications_title),
        actions = {
            if (allowed != true) {
                Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.notifications_allow))
                }
                OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_continue))
                }
            } else {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_continue))
                }
            }
        },
    ) {
        BodyText(stringResource(R.string.notifications_body))
        when (allowed) {
            true -> BodyText(stringResource(R.string.notifications_allowed))
            false -> BodyText(stringResource(R.string.notifications_denied))
            null -> Unit
        }
    }
}
