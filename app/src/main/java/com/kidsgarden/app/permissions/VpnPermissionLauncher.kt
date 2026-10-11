package com.kidsgarden.app.permissions

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Shows Android's VPN consent dialog through `VpnService.prepare` (T036). Call the returned
 * function only after the parent has read the VPN explanation. Consent alone creates no
 * grant and is not shown as protection.
 */
@Composable
fun rememberVpnPermissionLauncher(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onResult(result.resultCode == Activity.RESULT_OK)
    }
    return launch@{
        val consentIntent = try {
            VpnService.prepare(context)
        } catch (_: IllegalStateException) {
            onResult(false)
            return@launch
        }
        // Null means consent was already given.
        if (consentIntent == null) onResult(true) else launcher.launch(consentIntent)
    }
}
