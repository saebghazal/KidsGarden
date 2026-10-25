package com.kidsgarden.app.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.EffectAccess
import com.kidsgarden.app.domain.ParentFacingSnapshot
import com.kidsgarden.app.domain.EffectCycle
import com.kidsgarden.app.domain.ScreenEffect
import com.kidsgarden.app.ui.dashboard.methodStatuses
import com.kidsgarden.app.ui.setup.ModifySettingsPermissionScreen
import com.kidsgarden.app.ui.setup.OverlayPermissionScreen
import com.kidsgarden.app.ui.setup.overlaySettingsIntent
import com.kidsgarden.app.ui.setup.writeSettingsIntent
import com.kidsgarden.app.vpn.CompatibilityResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kidsgarden.app.AppContainer
import com.kidsgarden.app.data.SetupFlags
import com.kidsgarden.app.data.UnlockGraceSettings
import com.kidsgarden.app.domain.AuthorizationMode
import com.kidsgarden.app.domain.CallDetectionCapability
import com.kidsgarden.app.domain.DisplayMode
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.SetupLimitation
import com.kidsgarden.app.domain.VpnCapability
import com.kidsgarden.app.permissions.rememberNotificationPermissionLauncher
import com.kidsgarden.app.permissions.rememberPhoneStatePermissionLauncher
import com.kidsgarden.app.permissions.rememberVpnPermissionLauncher
import com.kidsgarden.app.ui.dashboard.DashboardScreen
import com.kidsgarden.app.ui.dashboard.DashboardViewModel
import com.kidsgarden.app.ui.dashboard.ParentActionScreen
import com.kidsgarden.app.ui.pin.ParentPinScreen
import com.kidsgarden.app.ui.pin.ParentPinViewModel
import com.kidsgarden.app.ui.session.ChildDurationScreen
import com.kidsgarden.app.ui.session.ChildSessionViewModel
import com.kidsgarden.app.ui.session.ChildStartedScreen
import com.kidsgarden.app.ui.settings.ChangePinScreen
import com.kidsgarden.app.ui.settings.ChangePinViewModel
import com.kidsgarden.app.ui.settings.LimitationsScreen
import com.kidsgarden.app.ui.settings.PrivacyScreen
import com.kidsgarden.app.ui.settings.SettingsScreen
import com.kidsgarden.app.ui.settings.SettingsUiState
import com.kidsgarden.app.ui.setup.CreatePinScreen
import com.kidsgarden.app.ui.setup.CreatePinViewModel
import com.kidsgarden.app.ui.setup.NotificationPermissionScreen
import com.kidsgarden.app.ui.setup.PhoneStatePermissionScreen
import com.kidsgarden.app.ui.setup.PrivacyExplanationScreen
import com.kidsgarden.app.ui.setup.VpnSetupScreen
import com.kidsgarden.app.ui.setup.WelcomeScreen
import com.kidsgarden.app.ui.setup.vpnSetupStatus
import com.kidsgarden.app.vpn.VpnUnavailableReason
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Route names. Protected routes register themselves through [guardedComposable]. */
object Routes {
    const val ENTRY = "entry"
    const val WELCOME = "setup/welcome"
    const val PRIVACY = "setup/privacy"
    const val CREATE_PIN = "setup/create-pin"
    const val PIN = "pin"
    const val NOTIFICATIONS = "setup/notifications"
    const val VPN = "setup/vpn"
    const val PHONE_STATE = "setup/phoneStatePermission"
    const val ACTIONS = "actions"
    const val CHILD_DURATION = "childDuration"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val CHANGE_PIN = "changePin"
    const val LIMITATIONS = "limitations"
    const val PRIVACY_INFO = "privacy"
    const val OVERLAY_ACCESS = "settings/overlayAccess"
    const val WRITE_SETTINGS_ACCESS = "settings/writeSettingsAccess"

    /** Inert confirmation after a child session starts; not protected, shows no controls. */
    const val CHILD_STARTED = "childStarted"

    /** Every route registered behind the parent-access guard. */
    internal val protected: MutableSet<String> = mutableSetOf()
}

/**
 * App navigation (contracts/parent-ui.md). Without a PIN, entry leads through
 * welcome → privacy → create PIN; creating the PIN opens the parent interface for the
 * notification and VPN steps. With a PIN, entry is the PIN screen, and an unfinished setup
 * resumes at the notification step after the PIN is entered (T038). Nothing here creates
 * a phone grant.
 *
 * No navigation argument, saved state, or intent extra carries a PIN or asserts
 * verification.
 */
@Composable
fun KidsGardenNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val session = container.sessionManager
    val snapshot by session.state.collectAsStateWithLifecycle()
    val setup by container.setupStatus.status.collectAsStateWithLifecycle()
    val vpnCapability by container.vpnController.capability.collectAsStateWithLifecycle()
    val callCapability by container.callMonitor.capability.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current

    // When parent access ends anywhere (lock, child start, PIN change), drop every
    // protected screen from the back stack so none can be restored.
    LaunchedEffect(navController) {
        session.parentUiAccessCleared.collect { navController.closeProtectedRoutes() }
    }

    // Leaving the protected graph by navigation also ends parent access.
    DisposableEffect(navController) {
        var wasProtected = false
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val isProtected = destination.route in Routes.protected
            if (wasProtected && !isProtected) session.dispatch(DomainEvent.ParentUiLeft)
            wasProtected = isProtected
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    /** A just-proven PIN opens the parent interface; navigate once the gate agrees. */
    fun openParentInterface(destination: String) {
        scope.launch {
            session.dispatch(DomainEvent.PinVerified)
            session.state.first { it.parentAccessActive }
            navController.replaceAll(destination)
        }
    }

    val accessActive = { snapshot.parentAccessActive }
    val denied = { navController.closeProtectedRoutes() }
    val credentialPresent = setup?.credentialPresent == true

    NavHost(navController = navController, startDestination = Routes.ENTRY) {
        composable(Routes.ENTRY) {
            LaunchedEffect(setup == null) {
                val status = setup ?: return@LaunchedEffect
                navController.replaceAll(if (status.credentialPresent) Routes.PIN else Routes.WELCOME)
            }
            Box(Modifier.fillMaxSize())
        }

        composable(Routes.WELCOME) {
            RedirectToPinIf(credentialPresent, navController)
            WelcomeScreen(onContinue = { navController.navigate(Routes.PRIVACY) })
        }
        composable(Routes.PRIVACY) {
            RedirectToPinIf(credentialPresent, navController)
            PrivacyExplanationScreen(onContinue = { navController.navigate(Routes.CREATE_PIN) })
        }
        composable(Routes.CREATE_PIN) {
            // No redirect here: enrollment itself refuses when a PIN already exists.
            val createPinViewModel = viewModel { CreatePinViewModel(container.pinManager) }
            CreatePinScreen(viewModel = createPinViewModel, onCreated = { openParentInterface(Routes.NOTIFICATIONS) })
        }

        composable(Routes.PIN) {
            if (setup != null && !credentialPresent) {
                LaunchedEffect(Unit) { navController.replaceAll(Routes.WELCOME) }
            }
            // During Parent Mode, opening the app grants parent access without a PIN.
            val parentModeAccess = snapshot.authorizationMode == AuthorizationMode.PARENT_MODE &&
                snapshot.parentAccessActive
            if (parentModeAccess) {
                LaunchedEffect(Unit) { navController.replaceAll(Routes.ACTIONS) }
            }
            val pinViewModel = viewModel { ParentPinViewModel(container.pinManager) }
            ParentPinScreen(viewModel = pinViewModel, time = container.time, deactivated = !snapshot.active, onAccepted = {
                openParentInterface(
                    when {
                        setup?.onboardingComplete != true -> Routes.NOTIFICATIONS
                        session.state.value.remainingPlayMillis != null && !session.state.value.unlockGraceActive -> Routes.DASHBOARD
                        else -> Routes.ACTIONS
                    },
                )
            })
        }

        guardedComposable(Routes.NOTIFICATIONS, accessActive, denied) {
            var asked by remember { mutableStateOf(false) }
            val request = rememberNotificationPermissionLauncher {
                asked = true
                container.setupStatus.refresh()
            }
            val granted = setup?.let { SetupLimitation.NOTIFICATIONS_DENIED !in it.limitations } == true
            NotificationPermissionScreen(
                allowed = if (granted) true else if (asked) false else null,
                onAllow = request,
                onContinue = { navController.navigate(Routes.VPN) },
            )
        }

        guardedComposable(Routes.VPN, accessActive, denied) {
            var consentDenied by remember { mutableStateOf(false) }
            var saveFailed by remember { mutableStateOf(false) }
            var compatibility by remember { mutableStateOf<CompatibilityResult?>(null) }
            var checking by remember { mutableStateOf(false) }
            // After consent the local compatibility check runs; a Pass leaves the VPN running (FR-032).
            val requestConsent = rememberVpnPermissionLauncher { granted ->
                consentDenied = !granted
                container.setupStatus.refresh()
                scope.launch {
                    checking = true
                    compatibility = container.vpnController.checkCompatibility()
                    checking = false
                    container.setupStatus.refresh()
                }
            }
            val effectsWorking = snapshot.enabledEffects.any { snapshot.effectAccess[it] == EffectAccess.WORKING }
            VpnSetupScreen(
                status = vpnSetupStatus(vpnCapability, consentDenied),
                saveFailed = saveFailed,
                compatibility = compatibility,
                checking = checking,
                effectsWorking = effectsWorking,
                onUseEffects = { navController.navigate(Routes.SETTINGS) },
                onSetUp = requestConsent,
                onFinish = {
                    scope.launch {
                        // Network dulling is saved on only when the VPN really runs; otherwise
                        // setup finishes with the working screen and sound effects.
                        val vpnRunning = vpnCapability.capability == VpnCapability.RUNNING
                        saveFailed = vpnRunning && container.settingsRepository.updateVpnPreference(true).isFailure
                        when {
                            saveFailed -> Unit
                            setup?.onboardingComplete == true -> navController.popBackStack()
                            else -> navController.navigate(Routes.PHONE_STATE)
                        }
                    }
                },
            )
        }

        guardedComposable(Routes.PHONE_STATE, accessActive, denied) {
            var asked by remember { mutableStateOf(false) }
            var saveFailed by remember { mutableStateOf(false) }
            val request = rememberPhoneStatePermissionLauncher {
                asked = true
                container.callMonitor.refresh()
            }
            val granted = callCapability == CallDetectionCapability.AVAILABLE
            PhoneStatePermissionScreen(
                allowed = if (granted) true else if (asked) false else null,
                saveFailed = saveFailed,
                onAllow = request,
                onContinue = {
                    if (setup?.onboardingComplete == true) {
                        navController.popBackStack()
                    } else {
                        scope.launch {
                            val saved = container.settingsRepository
                                .updateSetupFlags(SetupFlags(pinCreated = true, onboardingComplete = true)).isSuccess
                            saveFailed = !saved
                            if (saved) navController.replaceAll(Routes.ACTIONS)
                        }
                    }
                },
            )
        }

        guardedComposable(Routes.ACTIONS, accessActive, denied) {
            var switchFailed by remember { mutableStateOf(false) }
            // Display-only countdown for a running unlock grace; it never changes state.
            val graceRemaining by produceState<Long?>(null, snapshot) {
                while (true) {
                    value = snapshot.remainingPlayMillis?.takeIf { snapshot.unlockGraceActive }?.let { atSnapshot ->
                        if (snapshot.playPaused) atSnapshot
                        else (atSnapshot - (container.time.elapsedRealtimeMillis() - snapshot.snapshotElapsedMillis)).coerceAtLeast(0L)
                    }
                    delay(1_000L)
                }
            }
            ParentActionScreen(
                graceRemainingMillis = graceRemaining,
                // The Active/Deactivated switch at the top (FR-031). The PIN was entered to reach
                // this screen, so switching needs no second PIN; Deactivate still confirms once.
                active = snapshot.active,
                onActiveChange = { on ->
                    scope.launch {
                        switchFailed = (if (on) session.activate() else session.deactivate(confirmed = true)).isFailure
                    }
                },
                switchFailed = switchFailed,
                protectionReady = snapshot.displayMode != DisplayMode.SETUP_REQUIRED,
                onUseAsParent = {
                    snapshot.grantPermission?.let { permission ->
                        scope.launch {
                            // Fresh lock sample first, so the reducer decides on current state.
                            container.deviceEvents.sample()
                            session.dispatch(DomainEvent.StartParentRequested(permission))
                            val started = withTimeoutOrNull(START_CONFIRM_TIMEOUT_MS) {
                                session.state.first { it.authorizationMode == AuthorizationMode.PARENT_MODE }
                            }
                            // No confirmation screen: the app steps aside so the parent can
                            // use the phone. Reopening it during Parent Mode needs no PIN.
                            if (started != null) activity?.moveTaskToBack(true)
                        }
                    }
                },
                onExitParentMode = if (snapshot.authorizationMode == AuthorizationMode.PARENT_MODE) {
                    { session.dispatch(DomainEvent.ManualParentExit) }
                } else {
                    null
                },
                onGiveToChild = { navController.navigate(Routes.CHILD_DURATION) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // When nothing dulls the phone, name what is missing: an enabled effect without its
                // Android access comes first (the VPN may be switched off on purpose).
                limitedText = when {
                    missingAccess(snapshot, ScreenEffect.GREY_SCREEN) -> R.string.grey_needs_access
                    missingAccess(snapshot, ScreenEffect.LOWER_BRIGHTNESS) -> R.string.brightness_needs_access
                    else -> R.string.vpn_limited
                },
                fixLabel = when {
                    missingAccess(snapshot, ScreenEffect.GREY_SCREEN) -> R.string.overlay_open
                    missingAccess(snapshot, ScreenEffect.LOWER_BRIGHTNESS) -> R.string.settings_write_open
                    else -> R.string.action_vpn_setup
                },
                onVpnSetup = {
                    navController.navigate(
                        when {
                            missingAccess(snapshot, ScreenEffect.GREY_SCREEN) -> Routes.OVERLAY_ACCESS
                            missingAccess(snapshot, ScreenEffect.LOWER_BRIGHTNESS) -> Routes.WRITE_SETTINGS_ACCESS
                            else -> Routes.VPN
                        },
                    )
                },
                onStatus = { navController.navigate(Routes.DASHBOARD) },
            )
        }

        guardedComposable(Routes.DASHBOARD, accessActive, denied) {
            val context = LocalContext.current
            val vpnReason = remember {
                container.vpnController.capability.map { it.reason }
                    .stateIn(scope, SharingStarted.Eagerly, container.vpnController.capability.value.reason)
            }
            val dashboardViewModel = viewModel {
                DashboardViewModel(session.state, container.setupStatus.status, container.time, vpnReason)
            }
            val dashboard by dashboardViewModel.state.collectAsStateWithLifecycle()
            DashboardScreen(
                state = dashboard,
                onParentOptions = { navController.navigate(Routes.ACTIONS) },
                // Opened only when the parent taps it; Kids Garden never asks for the exemption (FR-033).
                onBatterySettings = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                onCheckPhone = { navController.navigate(Routes.VPN) },
            )
        }

        guardedComposable(Routes.SETTINGS, accessActive, denied) {
            val document by container.settings.collectAsStateWithLifecycle()
            var saveFailed by remember { mutableStateOf(false) }
            val repository = container.settingsRepository
            /** Every write reports its outcome; a failure leaves the old value and authorization. */
            fun save(write: suspend () -> Result<*>) {
                scope.launch {
                    val ok = write().isSuccess
                    saveFailed = !ok
                    session.dispatch(if (ok) DomainEvent.SettingsWriteSucceeded else DomainEvent.SettingsWriteFailed)
                }
            }
            SettingsScreen(
                state = SettingsUiState(
                    active = document?.active ?: true,
                    intensity = document?.dullIntensity ?: DullIntensity.MEDIUM,
                    softLandingSeconds = document?.softLandingDurationSeconds ?: 120,
                    vpnRunning = vpnCapability.capability == VpnCapability.RUNNING,
                    callPausePermissionMissing = callCapability == CallDetectionCapability.PERMISSION_MISSING,
                    saveFailed = saveFailed,
                    networkEnabled = document?.vpnDullingEnabled ?: true,
                    enabledEffects = document?.screenEffects?.enabled().orEmpty(),
                    buildUpSeconds = document?.screenEffects?.buildUpSeconds ?: EffectCycle.DEFAULT_BUILD_UP_SECONDS,
                    breakSeconds = document?.screenEffects?.breakSeconds ?: EffectCycle.DEFAULT_BREAK_SECONDS,
                    methods = methodStatuses(snapshot),
                    unlockGrace = document?.unlockGrace ?: UnlockGraceSettings(),
                ),
                onUnlockGrace = { g ->
                    save { repository.updateUnlockGrace(g.enabled, g.graceSeconds, g.fadeSeconds, g.restSeconds) }
                },
                onEffectSwitch = { effect, on ->
                    document?.let { current ->
                        val effects = current.screenEffects.enabled().let { if (on) it + effect else it - effect }
                        save {
                            repository.updateMethodSwitches(
                                network = current.vpnDullingEnabled,
                                grey = ScreenEffect.GREY_SCREEN in effects,
                                volume = ScreenEffect.LOWER_VOLUME in effects,
                                brightness = ScreenEffect.LOWER_BRIGHTNESS in effects,
                            )
                        }
                    }
                    // Access is explained and requested only when the parent turns the effect on.
                    if (on && snapshot.effectAccess[effect] != EffectAccess.WORKING) {
                        when (effect) {
                            ScreenEffect.GREY_SCREEN -> navController.navigate(Routes.OVERLAY_ACCESS)
                            ScreenEffect.LOWER_BRIGHTNESS -> navController.navigate(Routes.WRITE_SETTINGS_ACCESS)
                            ScreenEffect.LOWER_VOLUME -> Unit
                        }
                    }
                },
                onBuildUp = { seconds ->
                    val pause = document?.screenEffects?.breakSeconds ?: EffectCycle.DEFAULT_BREAK_SECONDS
                    save { repository.updateEffectTiming(seconds, pause) }
                },
                onBreak = { seconds ->
                    val buildUp = document?.screenEffects?.buildUpSeconds ?: EffectCycle.DEFAULT_BUILD_UP_SECONDS
                    save { repository.updateEffectTiming(buildUp, seconds) }
                },
                onCheckPhone = { navController.navigate(Routes.VPN) },
                onIntensity = { intensity -> save { repository.updateIntensity(intensity) } },
                onSoftLanding = { seconds -> save { repository.updateSoftLandingDuration(seconds) } },
                onStopVpn = {
                    scope.launch {
                        // Grants end before the VPN stops, so nothing runs unshaped under a grant.
                        session.dispatch(DomainEvent.VpnStopRequested)
                        withTimeoutOrNull(START_CONFIRM_TIMEOUT_MS) {
                            session.state.first { it.authorizationMode == AuthorizationMode.DULL }
                        }
                        save { repository.updateVpnPreference(false) }
                        container.vpnController.stop(VpnUnavailableReason.STOPPED)
                    }
                },
                onStartVpn = { navController.navigate(Routes.VPN) },
                onChangePin = { navController.navigate(Routes.CHANGE_PIN) },
                onLimitations = { navController.navigate(Routes.LIMITATIONS) },
                onPrivacy = { navController.navigate(Routes.PRIVACY_INFO) },
                onEnableCallPause = { navController.navigate(Routes.PHONE_STATE) },
            )
        }

        guardedComposable(Routes.OVERLAY_ACCESS, accessActive, denied) {
            val context = LocalContext.current
            OverlayPermissionScreen(
                granted = snapshot.effectAccess[ScreenEffect.GREY_SCREEN] == EffectAccess.WORKING,
                onOpenSettings = { context.startActivity(overlaySettingsIntent(context.packageName)) },
                onContinue = { navController.popBackStack() },
            )
        }

        guardedComposable(Routes.WRITE_SETTINGS_ACCESS, accessActive, denied) {
            val context = LocalContext.current
            ModifySettingsPermissionScreen(
                granted = snapshot.effectAccess[ScreenEffect.LOWER_BRIGHTNESS] == EffectAccess.WORKING,
                onOpenSettings = { context.startActivity(writeSettingsIntent(context.packageName)) },
                onContinue = { navController.popBackStack() },
            )
        }

        guardedComposable(Routes.CHANGE_PIN, accessActive, denied) {
            val changeViewModel = viewModel {
                // Success revokes every grant and all access; the gate then shows the PIN entry.
                ChangePinViewModel(container.pinManager) { session.dispatch(DomainEvent.PinChanged) }
            }
            val changeState by changeViewModel.state.collectAsStateWithLifecycle()
            ChangePinScreen(state = changeState, time = container.time, onSubmit = changeViewModel::submit)
        }

        guardedComposable(Routes.LIMITATIONS, accessActive, denied) { LimitationsScreen() }

        guardedComposable(Routes.PRIVACY_INFO, accessActive, denied) { PrivacyScreen() }

        guardedComposable(Routes.CHILD_DURATION, accessActive, denied) {
            val childViewModel = viewModel {
                ChildSessionViewModel { minutes ->
                    // Runs in the NavHost scope: starting clears parent access, which removes
                    // this screen (and its ViewModel) before the confirmation can navigate.
                    scope.async { startChild(container, minutes, navController) }.await()
                }
            }
            val childState by childViewModel.state.collectAsStateWithLifecycle()
            ChildDurationScreen(state = childState, onStart = childViewModel::start)
        }

        composable(Routes.CHILD_STARTED) {
            ChildStartedScreen()
            LaunchedEffect(Unit) {
                delay(CHILD_CONFIRMATION_MS)
                // The app then steps aside so the child lands on the home screen (owner change
                // 2026-10-19). Reopening it shows only the PIN entry.
                navController.replaceAll(Routes.PIN)
                activity?.moveTaskToBack(true)
            }
        }
    }
}

private const val START_CONFIRM_TIMEOUT_MS = 2_000L
private const val CHILD_CONFIRMATION_MS = 2_000L

/**
 * Requests a child session with the current one-use permission and, once the reducer has
 * committed it, shows the inert confirmation with an empty back stack behind it.
 */
private suspend fun startChild(container: AppContainer, minutes: Int, navController: NavController): Boolean {
    val session = container.sessionManager
    val permission = session.state.value.grantPermission ?: return false
    container.deviceEvents.sample()
    session.dispatch(DomainEvent.StartChildRequested(permission, minutes))
    val started = withTimeoutOrNull(START_CONFIRM_TIMEOUT_MS) {
        session.state.first { it.authorizationMode == AuthorizationMode.CHILD_PLAY_SESSION }
    } != null
    if (started) navController.replaceAll(Routes.CHILD_STARTED)
    return started
}

@Composable
private fun RedirectToPinIf(condition: Boolean, navController: NavController) {
    if (condition) LaunchedEffect(Unit) { navController.replaceAll(Routes.PIN) }
}

/** Navigates to [route] with an empty back stack behind it. */
fun NavController.replaceAll(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** Pops all protected destinations, leaving the neutral PIN entry. */
fun NavController.closeProtectedRoutes() {
    val current = currentDestination?.route ?: return
    if (current in Routes.protected) replaceAll(Routes.PIN)
}

/** The parent switched [effect] on but Android access for it is not granted. */
private fun missingAccess(snapshot: ParentFacingSnapshot, effect: ScreenEffect): Boolean =
    effect in snapshot.enabledEffects && snapshot.effectAccess[effect] == EffectAccess.ACCESS_MISSING
