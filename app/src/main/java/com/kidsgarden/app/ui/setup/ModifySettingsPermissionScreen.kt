package com.kidsgarden.app.ui.setup

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R

/**
 * Explanation before Android's "Modify system settings" page (T116, FR-036). Shown only
 * when the parent switches lower brightness on and access is missing.
 */
@Composable
fun ModifySettingsPermissionScreen(granted: Boolean, onOpenSettings: () -> Unit, onContinue: () -> Unit) {
    AccessExplanation(
        title = stringResource(R.string.settings_write_title),
        body = stringResource(R.string.settings_write_body),
        openLabel = stringResource(R.string.settings_write_open),
        missingText = stringResource(R.string.brightness_needs_access),
        granted = granted,
        onOpenSettings = onOpenSettings,
        onContinue = onContinue,
    )
}

/** Opens this app's "Modify system settings" page. */
fun writeSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", packageName, null))
