package com.kidsgarden.app.ui.setup

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R

/**
 * Explanation before Android's "Display over other apps" page (T116, FR-036). Shown only
 * when the parent switches the grey screen on and access is missing. Without access only
 * the grey screen is off; nothing else is blocked.
 */
@Composable
fun OverlayPermissionScreen(granted: Boolean, onOpenSettings: () -> Unit, onContinue: () -> Unit) {
    AccessExplanation(
        title = stringResource(R.string.overlay_title),
        body = stringResource(R.string.overlay_body),
        openLabel = stringResource(R.string.overlay_open),
        missingText = stringResource(R.string.grey_needs_access),
        granted = granted,
        onOpenSettings = onOpenSettings,
        onContinue = onContinue,
    )
}

/** Opens this app's "Display over other apps" page. */
fun overlaySettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null))

/** Shared layout for the two effect-access explanations. */
@Composable
internal fun AccessExplanation(
    title: String,
    body: String,
    openLabel: String,
    missingText: String,
    granted: Boolean,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    SetupScaffold(
        title = title,
        actions = {
            if (!granted) {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text(openLabel) }
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
        BodyText(body)
        BodyText(if (granted) stringResource(R.string.access_granted) else missingText)
    }
}
