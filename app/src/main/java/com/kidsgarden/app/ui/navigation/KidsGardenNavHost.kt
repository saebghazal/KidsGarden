package com.kidsgarden.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.kidsgarden.app.ui.dashboard.ParentModeScreen
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
    const val PARENT_MODE = "parentMode"
    const val CHILD_DURATION = "childDuration"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val CHANGE_PIN = "changePin"
    const val LIMITATIONS = "limitations"
    const val PRIVACY = "privacy"

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
            val pinViewModel = viewModel { ParentPinViewModel(container.pinManager) }
            ParentPinScreen(viewModel = pinViewModel, time = container.time, onAccepted = {
                openParentInterface(
                    when {
                        setup?.onboardingComplete != true -> Routes.NOTIFICATIONS
                        session.state.value.authorizationMode == AuthorizationMode.PARENT_MODE -> Routes.PARENT_MODE
                        session.state.value.remainingPlayMillis != null -> Routes.DASHBOARD
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
            val requestConsent = rememberVpnPermissionLauncher { granted ->
                consentDenied = !granted
                container.setupStatus.refresh()
                if (granted) scope.launch { container.vpnController.start() }
            }
            VpnSetupScreen(
                status = vpnSetupStatus(vpnCapability, consentDenied),
                saveFailed = saveFailed,
                onSetUp = requestConsent,
                onFinish = {
                    scope.launch {
                        saveFailed = container.settingsRepository.updateVpnPreference(true).isFailure
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
            ParentActionScreen(
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
                            if (started != null) navController.replaceAll(Routes.PARENT_MODE)
                        }
                    }
                },
                onGiveToChild = { navController.navigate(Routes.CHILD_DURATION) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onVpnSetup = { navController.navigate(Routes.VPN) },
                onStatus = { navController.navigate(Routes.DASHBOARD) },
            )
        }

        guardedComposable(Routes.DASHBOARD, accessActive, denied) {
            val dashboardViewModel = viewModel {
                DashboardViewModel(session.state, container.setupStatus.status, container.time)
            }
            val dashboard by dashboardViewModel.state.collectAsStateWithLifecycle()
            DashboardScreen(state = dashboard, onParentOptions = { navController.navigate(Routes.ACTIONS) })
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
                    intensity = document?.dullIntensity ?: DullIntensity.MEDIUM,
                    softLandingSeconds = document?.softLandingDurationSeconds ?: 120,
                    vpnRunning = vpnCapability.capability == VpnCapability.RUNNING,
                    callPausePermissionMissing = callCapability == CallDetectionCapability.PERMISSION_MISSING,
                    saveFailed = saveFailed,
                ),
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
                onPrivacy = { navController.navigate(Routes.PRIVACY) },
                onEnableCallPause = { navController.navigate(Routes.PHONE_STATE) },
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

        guardedComposable(Routes.PRIVACY, accessActive, denied) { PrivacyScreen() }

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
                navController.replaceAll(Routes.PIN)
            }
        }

        guardedComposable(Routes.PARENT_MODE, accessActive, denied) {
            // Exiting revokes Parent Mode and parent access; the gate then returns to the PIN.
            ParentModeScreen(onExit = { session.dispatch(DomainEvent.ManualParentExit) })
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
