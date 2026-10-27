package com.kidsgarden.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.DisplayMode
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.ScreenEffect
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.ui.dashboard.DashboardScreen
import com.kidsgarden.app.ui.dashboard.DashboardUiState
import com.kidsgarden.app.ui.navigation.Routes
import com.kidsgarden.app.ui.navigation.closeProtectedRoutes
import com.kidsgarden.app.ui.navigation.guardedComposable
import com.kidsgarden.app.ui.settings.ChangePinScreen
import com.kidsgarden.app.ui.settings.ChangePinViewModel
import com.kidsgarden.app.ui.settings.LimitationsScreen
import com.kidsgarden.app.ui.settings.PrivacyScreen
import com.kidsgarden.app.ui.settings.SettingsScreen
import com.kidsgarden.app.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dashboard and settings (T079). Every assertion uses strings.xml, never literals. */
@RunWith(AndroidJUnit4::class)
class DashboardSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private val time = object : TimeProvider {
        override fun elapsedRealtimeMillis() = 0L
        override fun epochMillis() = 0L
    }

    private fun dashboard(
        mode: DisplayMode = DisplayMode.DULL,
        vpnRunning: Boolean = true,
        remaining: Long? = null,
        paused: Boolean = false,
        callLimitation: Boolean = false,
    ) = DashboardUiState(mode, vpnRunning, DullIntensity.MEDIUM, setupComplete = true, remaining, paused, callLimitation)

    @Test
    fun eachDisplayModeHasItsOwnStatus() {
        val expected = mapOf(
            DisplayMode.DULL to R.string.mode_dull,
            DisplayMode.SETUP_REQUIRED to R.string.vpn_limited,
            DisplayMode.PARENT_MODE to R.string.mode_parent,
            DisplayMode.CHILD_PLAY_SESSION to R.string.mode_child,
            DisplayMode.SOFT_LANDING to R.string.mode_soft_landing,
        )
        val mode = mutableStateOf(DisplayMode.DULL)
        compose.setContent {
            DashboardScreen(state = dashboard(mode = mode.value, vpnRunning = mode.value != DisplayMode.SETUP_REQUIRED), onParentOptions = {})
        }
        for ((m, id) in expected) {
            compose.runOnIdle { mode.value = m }
            compose.onAllNodesWithText(text(id))[0].assertIsDisplayed()
        }
    }

    @Test
    fun remainingPlayAndPauseAreShown() {
        compose.setContent { DashboardScreen(state = dashboard(DisplayMode.CHILD_PLAY_SESSION, remaining = 125_000L), onParentOptions = {}) }
        compose.onNodeWithText(context.getString(R.string.dashboard_remaining, "2:05")).assertIsDisplayed()
    }

    @Test
    fun pausedPlayShowsThePauseNotACountdown() {
        compose.setContent {
            DashboardScreen(state = dashboard(DisplayMode.CHILD_PLAY_SESSION, remaining = 125_000L, paused = true), onParentOptions = {})
        }
        compose.onNodeWithText(text(R.string.dashboard_paused)).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.dashboard_remaining, "2:05")).assertCountEquals(0)
    }

    @Test
    fun callLimitationAppearsOnlyWhenDetectionIsUnavailable() {
        compose.setContent { DashboardScreen(state = dashboard(callLimitation = true), onParentOptions = {}) }
        compose.onNodeWithText(text(R.string.call_limitation)).assertIsDisplayed()
    }

    @Test
    fun noCallLimitationWhenDetectionWorks() {
        compose.setContent { DashboardScreen(state = dashboard(callLimitation = false), onParentOptions = {}) }
        compose.onAllNodesWithText(text(R.string.call_limitation)).assertCountEquals(0)
    }

    private fun settingsState(
        vpnRunning: Boolean = true,
        callMissing: Boolean = false,
        saveFailed: Boolean = false,
        networkEnabled: Boolean = true,
        effects: Set<ScreenEffect> = setOf(ScreenEffect.LOWER_VOLUME),
    ) = SettingsUiState(
        DullIntensity.MEDIUM, 120, vpnRunning, callMissing, saveFailed,
        networkEnabled = networkEnabled, enabledEffects = effects,
    )

    @Test
    fun eachSettingChangeIsReported() {
        var intensity: DullIntensity? = null
        var ramp: Int? = null
        var stops = 0
        compose.setContent {
            SettingsScreen(
                state = settingsState(),
                onIntensity = { intensity = it },
                onSoftLanding = { ramp = it },
                onStopVpn = { stops++ },
                onStartVpn = {},
                onChangePin = {},
                onLimitations = {},
                onPrivacy = {},
                onEnableCallPause = {},
            )
        }
        compose.onNodeWithText(text(R.string.intensity_strong)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.stepper_decrease, text(R.string.settings_ramp_heading)))
            .performScrollTo().performClick()
        // Network dulling off stops the VPN (another method is still on).
        compose.onNodeWithText(text(R.string.method_network)).performScrollTo().performClick()
        assertEquals(DullIntensity.STRONG, intensity)
        assertEquals(60, ramp)
        assertEquals(1, stops)
        compose.onNodeWithText(text(R.string.settings_vpn_android_note)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun stoppedVpnOffersRestartAndLimitedStatus() {
        var starts = 0
        compose.setContent {
            SettingsScreen(
                state = settingsState(vpnRunning = false, networkEnabled = false),
                onIntensity = {}, onSoftLanding = {}, onStopVpn = {}, onStartVpn = { starts++ },
                onChangePin = {}, onLimitations = {}, onPrivacy = {}, onEnableCallPause = {},
            )
        }
        compose.onNodeWithText(text(R.string.vpn_limited)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.method_network)).performScrollTo().performClick()
        assertEquals(1, starts)
    }

    @Test
    fun enableCallPauseOnlyWhenPermissionMissing() {
        var opened = 0
        compose.setContent {
            SettingsScreen(
                state = settingsState(callMissing = true),
                onIntensity = {}, onSoftLanding = {}, onStopVpn = {}, onStartVpn = {},
                onChangePin = {}, onLimitations = {}, onPrivacy = {}, onEnableCallPause = { opened++ },
            )
        }
        compose.onNodeWithText(text(R.string.settings_enable_call_pause)).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun failedSaveShowsStorageMessage() {
        compose.setContent {
            SettingsScreen(
                state = settingsState(saveFailed = true),
                onIntensity = {}, onSoftLanding = {}, onStopVpn = {}, onStartVpn = {},
                onChangePin = {}, onLimitations = {}, onPrivacy = {}, onEnableCallPause = {},
            )
        }
        compose.onNodeWithText(text(R.string.settings_save_failed)).assertIsDisplayed()
    }

    @Test
    fun changePinMessagesUseResourceText() {
        val cases = mapOf(
            ChangePinViewModel.State.CurrentMismatch to R.string.pin_mismatch,
            ChangePinViewModel.State.InvalidNewPin to R.string.pin_invalid_format,
            ChangePinViewModel.State.NewPinMismatch to R.string.pin_confirmation_mismatch,
            ChangePinViewModel.State.StorageFailed to R.string.settings_save_failed,
        )
        val current = mutableStateOf<ChangePinViewModel.State>(ChangePinViewModel.State.Idle)
        compose.setContent { ChangePinScreen(state = current.value, time = time, onSubmit = { _, _, _ -> }) }
        for ((state, id) in cases) {
            compose.runOnIdle { current.value = state }
            compose.onNodeWithText(text(id)).assertIsDisplayed()
        }
    }

    @Test
    fun limitationsUseExactCopyAndMakeNoOverclaims() {
        compose.setContent { LimitationsScreen() }
        compose.onNodeWithText(text(R.string.limits_uninstall)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.call_limitation)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.limit_forgot_pin)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun privacyUsesExactCopy() {
        compose.setContent { PrivacyScreen() }
        compose.onNodeWithText(text(R.string.privacy_summary)).assertIsDisplayed()
    }

    @Test
    fun settingsAndDashboardRequireThePin() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Routes.PIN) {
                composable(Routes.PIN) { Text("pin-marker") }
                for (route in listOf(Routes.SETTINGS, Routes.DASHBOARD, Routes.CHANGE_PIN, Routes.LIMITATIONS, Routes.PRIVACY_INFO)) {
                    guardedComposable(route, { false }, { nav.closeProtectedRoutes() }) { Text("protected-marker") }
                }
            }
        }
        for (route in listOf(Routes.SETTINGS, Routes.DASHBOARD, Routes.CHANGE_PIN, Routes.LIMITATIONS, Routes.PRIVACY_INFO)) {
            compose.runOnIdle { nav.navigate(route) }
            compose.waitForIdle()
            compose.onAllNodesWithText("protected-marker").assertCountEquals(0)
            compose.runOnIdle { assertEquals(Routes.PIN, nav.currentDestination?.route) }
        }
    }
}
