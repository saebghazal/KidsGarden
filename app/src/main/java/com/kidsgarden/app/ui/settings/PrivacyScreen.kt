package com.kidsgarden.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R
import com.kidsgarden.app.ui.setup.BodyText
import com.kidsgarden.app.ui.setup.SetupScaffold

/** Privacy and the local VPN explained (T076): settings only, on this phone, no logs. */
@Composable
fun PrivacyScreen() {
    SetupScaffold(title = stringResource(R.string.privacy_screen_title), actions = {}) {
        BodyText(stringResource(R.string.privacy_summary))
        BodyText(stringResource(R.string.privacy_no_logs))
        BodyText(stringResource(R.string.privacy_local_vpn))
        BodyText(stringResource(R.string.privacy_no_account))
    }
}
