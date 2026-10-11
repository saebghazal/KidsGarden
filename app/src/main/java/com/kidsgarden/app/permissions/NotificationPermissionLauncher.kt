package com.kidsgarden.app.permissions

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Requests POST_NOTIFICATIONS (T037). Call the returned function only from the parent
 * explanation screen's button, so the explanation always comes first. Denial is respected:
 * there is no repeat prompt.
 */
@Composable
fun rememberNotificationPermissionLauncher(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
