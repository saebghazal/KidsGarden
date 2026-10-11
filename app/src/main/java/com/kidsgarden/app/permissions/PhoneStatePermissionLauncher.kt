package com.kidsgarden.app.permissions

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Requests READ_PHONE_STATE (T062), used only for per-subscription call state. Call the
 * returned function only from the parent explanation screen's button. Denial never blocks
 * setup, sessions, or dulling; nothing about the choice is stored.
 */
@Composable
fun rememberPhoneStatePermissionLauncher(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return { launcher.launch(Manifest.permission.READ_PHONE_STATE) }
}
