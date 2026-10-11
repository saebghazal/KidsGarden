package com.kidsgarden.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R

/**
 * Local-only operation, no content or logs, normal-app limits, no account or factory
 * reset (T034).
 */
@Composable
fun PrivacyExplanationScreen(onContinue: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.privacy_title),
        actions = {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_continue))
            }
        },
    ) {
        BodyText(stringResource(R.string.privacy_summary))
        BodyText(stringResource(R.string.privacy_local_vpn))
        BodyText(stringResource(R.string.limits_uninstall))
        BodyText(stringResource(R.string.privacy_no_account))
    }
}
