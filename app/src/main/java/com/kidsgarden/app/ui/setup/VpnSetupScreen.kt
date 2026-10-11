package com.kidsgarden.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.VpnCapability
import com.kidsgarden.app.vpn.VpnCapabilitySnapshot
import com.kidsgarden.app.vpn.VpnUnavailableReason

/** What the VPN step shows. Derived from live capability, never from the saved preference. */
enum class VpnSetupStatus { NOT_STARTED, STARTING, RUNNING, DENIED, FAILED }

fun vpnSetupStatus(capability: VpnCapabilitySnapshot, consentDenied: Boolean): VpnSetupStatus = when {
    capability.capability == VpnCapability.RUNNING -> VpnSetupStatus.RUNNING
    capability.capability == VpnCapability.STARTING -> VpnSetupStatus.STARTING
    consentDenied -> VpnSetupStatus.DENIED
    capability.reason in NOT_STARTED_REASONS -> VpnSetupStatus.NOT_STARTED
    else -> VpnSetupStatus.FAILED
}

private val NOT_STARTED_REASONS = setOf(
    null,
    VpnUnavailableReason.NOT_STARTED,
    VpnUnavailableReason.CONSENT_MISSING,
    VpnUnavailableReason.STOPPED,
)

/**
 * VPN step (T036): the explanation is on screen before the parent can open Android's
 * consent dialog. Denial is calm and resumable; "active" appears only once forwarding works.
 */
@Composable
fun VpnSetupScreen(
    status: VpnSetupStatus,
    onSetUp: () -> Unit,
    onFinish: () -> Unit,
    saveFailed: Boolean = false,
) {
    SetupScaffold(
        title = stringResource(R.string.vpn_title),
        actions = {
            when (status) {
                VpnSetupStatus.RUNNING -> Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_finish))
                }
                VpnSetupStatus.STARTING -> {
                    val starting = stringResource(R.string.vpn_status_starting)
                    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = starting })
                }
                VpnSetupStatus.NOT_STARTED -> Button(onClick = onSetUp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vpn_set_up))
                }
                VpnSetupStatus.DENIED, VpnSetupStatus.FAILED -> Button(onClick = onSetUp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_try_again))
                }
            }
        },
    ) {
        BodyText(stringResource(R.string.vpn_body))
        if (status == VpnSetupStatus.NOT_STARTED) BodyText(stringResource(R.string.vpn_prompt_notice))
        val statusText = when (status) {
            VpnSetupStatus.NOT_STARTED -> R.string.vpn_limited
            VpnSetupStatus.STARTING -> R.string.vpn_status_starting
            VpnSetupStatus.RUNNING -> R.string.vpn_status_running
            VpnSetupStatus.DENIED -> R.string.vpn_status_denied
            VpnSetupStatus.FAILED -> R.string.vpn_status_failed
        }
        Text(
            text = stringResource(statusText),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (saveFailed) BodyText(stringResource(R.string.settings_save_failed))
    }
}
