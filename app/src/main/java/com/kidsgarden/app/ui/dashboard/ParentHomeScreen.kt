package com.kidsgarden.app.ui.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.DisplayMode
import com.kidsgarden.app.ui.setup.BodyText
import com.kidsgarden.app.ui.setup.SetupScaffold

/**
 * Temporary parent landing screen until Parent Actions (T054) and the dashboard (T073).
 * Shows only real protection status: "active" requires the VPN to be forwarding.
 */
@Composable
fun ParentHomeScreen(displayMode: DisplayMode, onVpnSetup: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.parent_home_title),
        actions = {
            OutlinedButton(onClick = onVpnSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.parent_home_vpn))
            }
        },
    ) {
        val status = if (displayMode == DisplayMode.SETUP_REQUIRED) R.string.vpn_limited else R.string.vpn_status_running
        BodyText(stringResource(status))
        BodyText(stringResource(R.string.privacy_summary))
    }
}
