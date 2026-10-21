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
 *
 * [onExitParentMode] is non-null while Parent Mode is on (T055): the screen then offers
 * "Exit Parent Mode" (no PIN needed) in place of "Use phone as parent".
 *
 * [onActiveChange] puts the Active/Deactivated switch at the top (FR-031). While
 * Deactivated ([active] false) grants are not offered; only the switch and Settings remain.
 */
@Composable
fun ParentActionScreen(
    protectionReady: Boolean,
    onUseAsParent: () -> Unit,
    onGiveToChild: (() -> Unit)?,
    onSettings: (() -> Unit)?,
    onVpnSetup: () -> Unit,
    onStatus: (() -> Unit)? = null,
    onExitParentMode: (() -> Unit)? = null,
    active: Boolean = true,
    onActiveChange: ((Boolean) -> Unit)? = null,
    switchFailed: Boolean = false,
    /** Why grants are unavailable, and the button that fixes it (VPN setup by default). */
    limitedText: Int = R.string.vpn_limited,
    fixLabel: Int = R.string.action_vpn_setup,
) {
    SetupScaffold(
        title = stringResource(R.string.actions_title),
        actions = {
            if (active) {
                if (onExitParentMode != null) {
                    OutlinedButton(onClick = onExitParentMode, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.parent_mode_exit))
                    }
                } else {
                    Button(onClick = onUseAsParent, enabled = protectionReady, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_use_as_parent))
                    }
                }
                if (onGiveToChild != null) {
                    Button(onClick = onGiveToChild, enabled = protectionReady, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_give_to_child))
                    }
                }
            }
            if (onSettings != null) {
                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_settings))
                }
            }
            if (active && onStatus != null) {
                OutlinedButton(onClick = onStatus, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_status))
                }
            }
            if (active && !protectionReady) {
                OutlinedButton(onClick = onVpnSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(fixLabel))
                }
            }
        },
    ) {
        if (onActiveChange != null) ActiveSwitchCard(active = active, onActiveChange = onActiveChange, failed = switchFailed)
        if (active) {
            BodyText(
                stringResource(
                    when {
                        onExitParentMode != null -> R.string.parent_mode_confirmation
                        protectionReady -> R.string.vpn_status_running
                        else -> limitedText
                    },
                ),
            )
        }
    }
}
