package com.kidsgarden.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kidsgarden.app.R
import com.kidsgarden.app.ui.setup.BodyText
import com.kidsgarden.app.ui.setup.SetupScaffold

/**
 * Honest V1 limits (T076): normal installation, no full control or app blocking,
 * best-effort dulling, the exact call limitation, VPN conflicts, forgotten-PIN help, and
 * future stronger protection. Makes no uninstall-proof or total-blocking claim.
 */
@Composable
fun LimitationsScreen() {
    SetupScaffold(title = stringResource(R.string.limitations_title), actions = {}) {
        BodyText(stringResource(R.string.limits_uninstall))
        BodyText(stringResource(R.string.limit_no_control))
        BodyText(stringResource(R.string.limit_best_effort))
        BodyText(stringResource(R.string.call_limitation))
        BodyText(stringResource(R.string.limit_other_vpn))
        BodyText(stringResource(R.string.limit_forgot_pin))
        BodyText(stringResource(R.string.limit_future))
    }
}
