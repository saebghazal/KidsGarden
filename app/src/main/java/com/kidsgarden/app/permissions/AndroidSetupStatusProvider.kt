package com.kidsgarden.app.permissions

import com.kidsgarden.app.data.SettingsDocument
import com.kidsgarden.app.domain.SetupInputs
import com.kidsgarden.app.domain.SetupPolicy
import com.kidsgarden.app.domain.SetupStatus
import com.kidsgarden.app.domain.VpnCapability
import com.kidsgarden.app.vpn.VpnCapabilitySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Combines live consent, real forwarding health, notification permission, and storage into
 * [SetupStatus] (T033). The saved VPN preference is not an input: it is never proof of
 * protection. [status] is null until settings have loaded.
 *
 * Permissions have no change callback, so screens call [refresh] when they resume and after
 * each system prompt returns.
 */
class AndroidSetupStatusProvider(
    private val permissions: PermissionStatusProvider,
    vpnCapability: StateFlow<VpnCapabilitySnapshot>,
    settings: StateFlow<SettingsDocument?>,
    storageAvailable: StateFlow<Boolean>,
    scope: CoroutineScope,
) {
    private val permissionChecks = MutableStateFlow(0)

    val status: StateFlow<SetupStatus?> =
        combine(settings, vpnCapability, storageAvailable, permissionChecks) { document, vpn, storage, _ ->
            if (document == null && storage) {
                null // still loading
            } else {
                SetupPolicy.evaluate(
                    SetupInputs(
                        credentialPresent = document?.pinCredential != null,
                        onboardingComplete = document?.setup?.onboardingComplete == true,
                        vpnConsentGranted = permissions.vpnConsentGranted(),
                        forwardingHealthy = vpn.capability == VpnCapability.RUNNING,
                        notificationsAllowed = permissions.notificationsAllowed(),
                        storageAvailable = storage,
                    ),
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun refresh() {
        permissionChecks.update { it + 1 }
    }
}
