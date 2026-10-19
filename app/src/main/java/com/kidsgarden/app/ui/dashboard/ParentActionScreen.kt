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
 * [onActivate] is non-null while Kids Garden is Deactivated (FR-031): the screen then offers
 * only "Activate Kids Garden" and Settings.
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
    onActivate: (() -> Unit)? = null,
    activateFailed: Boolean = false,
) {
    if (onActivate != null) {
        DeactivatedActions(onActivate, onSettings, activateFailed)
        return
    }
    SetupScaffold(
        title = stringResource(R.string.actions_title),
        actions = {
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
            if (onSettings != null) {
                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_settings))
                }
            }
            if (onStatus != null) {
                OutlinedButton(onClick = onStatus, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_status))
                }
            }
            if (!protectionReady) {
                OutlinedButton(onClick = onVpnSetup, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_vpn_setup))
                }
            }
        },
    ) {
        BodyText(
            stringResource(
                when {
                    onExitParentMode != null -> R.string.parent_mode_confirmation
                    protectionReady -> R.string.vpn_status_running
                    else -> R.string.vpn_limited
                },
            ),
        )
    }
}

/** While Deactivated: plain status, Activate, and Settings only. */
@Composable
private fun DeactivatedActions(onActivate: () -> Unit, onSettings: (() -> Unit)?, activateFailed: Boolean) {
    SetupScaffold(
        title = stringResource(R.string.actions_title),
        actions = {
            Button(onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_activate))
            }
            if (onSettings != null) {
                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_settings))
                }
            }
        },
    ) {
        BodyText(stringResource(R.string.deactivated_status))
        if (activateFailed) BodyText(stringResource(R.string.activate_failed))
    }
}
