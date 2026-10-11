package com.kidsgarden.app.ui.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R
import com.kidsgarden.app.ui.setup.BodyText
import com.kidsgarden.app.ui.setup.SetupScaffold

/**
 * What the parent can do after entering the PIN (T054). Entering the PIN changed nothing on
 * the phone; only these explicit actions start a grant.
 *
 * [protectionReady] is false while setup or the VPN is incomplete: grants are unavailable
 * then, and the screen says so calmly and offers VPN setup. [onGiveToChild] and
 * [onSettings] are null until those stories wire their screens (T063, T074).
 */
@Composable
fun ParentActionScreen(
    protectionReady: Boolean,
    onUseAsParent: () -> Unit,
    onGiveToChild: (() -> Unit)?,
    onSettings: (() -> Unit)?,
    onVpnSetup: () -> Unit,
) {
    SetupScaffold(
        title = stringResource(R.string.actions_title),
        actions = {
            Button(onClick = onUseAsParent, enabled = protectionReady, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_use_as_parent))
            }
            if (onGiveToChild != null) {
                Button(onClick = onGiveToChild, enabled = protectionReady, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_give_to_child))
                }
            }
            if (onSettings != null) {
                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_settings))
                }
            }
            if (!protectionReady) {
                OutlinedButton(onClick = onVpnSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_vpn_setup))
                }
            }
        },
    ) {
        BodyText(stringResource(if (protectionReady) R.string.vpn_status_running else R.string.vpn_limited))
    }
}
