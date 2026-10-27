package com.kidsgarden.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.DisplayMode
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.ScreenEffect
import com.kidsgarden.app.ui.dashboard.DashboardScreen
import com.kidsgarden.app.ui.dashboard.DashboardUiState
import com.kidsgarden.app.ui.dashboard.DullingMethod
import com.kidsgarden.app.ui.dashboard.MethodState
import com.kidsgarden.app.ui.dashboard.MethodStatus
import com.kidsgarden.app.ui.settings.BypassAppsScreen
import com.kidsgarden.app.ui.settings.LaunchableApp
import com.kidsgarden.app.ui.settings.SettingsScreen
import com.kidsgarden.app.ui.settings.SettingsUiState
import com.kidsgarden.app.ui.setup.ModifySettingsPermissionScreen
import com.kidsgarden.app.ui.setup.OverlayPermissionScreen
import com.kidsgarden.app.ui.setup.VpnSetupScreen
import com.kidsgarden.app.ui.setup.VpnSetupStatus
import com.kidsgarden.app.ui.setup.compatibilityReason
import com.kidsgarden.app.vpn.CompatibilityFailure
import com.kidsgarden.app.vpn.CompatibilityResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dulling-method settings, access explanations, compatibility and battery guidance (T122). */
@RunWith(AndroidJUnit4::class)
class MethodSettingsFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun settings(network: Boolean = true, effects: Set<ScreenEffect> = emptySet(), methods: List<MethodStatus> = emptyList()) =
        SettingsUiState(
            DullIntensity.MEDIUM, 120, vpnRunning = network, callPausePermissionMissing = false, saveFailed = false,
            networkEnabled = network, enabledEffects = effects, methods = methods,
        )

    private fun setSettings(
        state: SettingsUiState,
        onEffect: (ScreenEffect, Boolean) -> Unit = { _, _ -> },
        onStop: () -> Unit = {},
        onCheck: () -> Unit = {},
        onBuildUp: (Int) -> Unit = {},
        onBreak: (Int) -> Unit = {},
    ) {
        compose.setContent {
            SettingsScreen(
                state = state,
                onIntensity = {}, onSoftLanding = {}, onStopVpn = onStop, onStartVpn = {},
                onChangePin = {}, onLimitations = {}, onPrivacy = {}, onEnableCallPause = {},
                onEffectSwitch = onEffect, onBuildUp = onBuildUp, onBreak = onBreak, onCheckPhone = onCheck,
            )
        }
    }

    @Test
    fun effectSwitchesReportChanges() {
        val changes = mutableListOf<Pair<ScreenEffect, Boolean>>()
        setSettings(settings(effects = setOf(ScreenEffect.LOWER_VOLUME)), onEffect = { e, on -> changes += e to on })

        compose.onNodeWithText(text(R.string.method_grey)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.method_volume)).performScrollTo().performClick()

        assertEquals(listOf(ScreenEffect.GREY_SCREEN to true, ScreenEffect.LOWER_VOLUME to false), changes)
    }

    @Test
    fun lastMethodCannotBeSwitchedOff() {
        var stops = 0
        setSettings(settings(network = true), onStop = { stops++ })

        compose.onNodeWithText(text(R.string.method_network)).performScrollTo().performClick()

        assertEquals(0, stops)
        compose.onNodeWithText(text(R.string.method_last)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun timingsAndCheckAreReported() {
        var buildUp = 0
        var pause = -1
        var checks = 0
        setSettings(settings(), onBuildUp = { buildUp = it }, onBreak = { pause = it }, onCheck = { checks++ })

        compose.onNodeWithText(text(R.string.compat_check)).performScrollTo().performClick()
        // Up/down arrows: break 1 min → no break; build-up 2 min → 3 min.
        compose.onNodeWithContentDescription(context.getString(R.string.stepper_decrease, text(R.string.effect_break_heading)))
            .performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.stepper_increase, text(R.string.effect_build_up_heading)))
            .performScrollTo().performClick()

        assertEquals(1, checks)
        assertEquals(0, pause)
        assertEquals(180, buildUp)
    }

    @Test
    fun missingAccessIsExplainedPerEffect() {
        setSettings(
            settings(
                effects = setOf(ScreenEffect.GREY_SCREEN, ScreenEffect.LOWER_BRIGHTNESS),
                methods = listOf(
                    MethodStatus(DullingMethod.GREY, MethodState.ACCESS_MISSING),
                    MethodStatus(DullingMethod.BRIGHTNESS, MethodState.ACCESS_MISSING),
                ),
            ),
        )
        compose.onNodeWithText(text(R.string.grey_needs_access)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.brightness_needs_access)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun accessExplanationsComeBeforeAndroidSettings() {
        var opened = 0
        compose.setContent { OverlayPermissionScreen(granted = false, onOpenSettings = { opened++ }, onContinue = {}) }
        compose.onNodeWithText(text(R.string.overlay_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.overlay_open)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun modifySettingsExplanationShowsGrantedState() {
        compose.setContent { ModifySettingsPermissionScreen(granted = true, onOpenSettings = {}, onContinue = {}) }
        compose.onNodeWithText(text(R.string.settings_write_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.access_granted)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.settings_write_open)).assertCountEquals(0)
    }

    @Test
    fun compatibilityPassIsShown() {
        compose.setContent {
            VpnSetupScreen(status = VpnSetupStatus.RUNNING, onSetUp = {}, onFinish = {}, compatibility = CompatibilityResult.Pass)
        }
        compose.onNodeWithText(text(R.string.compat_pass)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun eachCompatibilityFailureShowsOneReasonAndOffersEffects() {
        val result = androidx.compose.runtime.mutableStateOf<CompatibilityResult>(CompatibilityResult.Pass)
        var useEffects = 0
        compose.setContent {
            VpnSetupScreen(
                status = VpnSetupStatus.FAILED, onSetUp = {}, onFinish = {},
                compatibility = result.value, onUseEffects = { useEffects++ },
            )
        }
        for (failure in CompatibilityFailure.entries) {
            compose.runOnIdle { result.value = CompatibilityResult.Failed(failure) }
            compose.onNodeWithText(text(compatibilityReason(failure))).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.compat_offer_effects)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.action_use_effects)).performScrollTo().performClick()
        assertEquals(1, useEffects)
    }

    @Test
    fun setupCanFinishWithAWorkingEffect() {
        var finished = 0
        compose.setContent {
            VpnSetupScreen(
                status = VpnSetupStatus.FAILED, onSetUp = {}, onFinish = { finished++ },
                compatibility = CompatibilityResult.Failed(CompatibilityFailure.OTHER_VPN), effectsWorking = true,
            )
        }
        compose.onNodeWithText(text(R.string.action_finish)).performScrollTo().performClick()
        assertEquals(1, finished)
    }

    private fun dashboard(mode: DisplayMode = DisplayMode.DULL, backgroundStopped: Boolean = false) = DashboardUiState(
        mode, vpnRunning = false, DullIntensity.MEDIUM, setupComplete = true, remainingPlayMillis = null, playPaused = false,
        showCallLimitation = false, backgroundStopped = backgroundStopped,
        methods = listOf(MethodStatus(DullingMethod.NETWORK, MethodState.NOT_RUNNING)),
    )

    @Test
    fun backgroundStopOffersBatterySettingsOnlyOnTap() {
        var opened = 0
        compose.setContent { DashboardScreen(state = dashboard(backgroundStopped = true), onParentOptions = {}, onBatterySettings = { opened++ }) }
        compose.onNodeWithText(text(R.string.background_stopped)).performScrollTo().assertIsDisplayed()
        assertEquals(0, opened)
        compose.onNodeWithText(text(R.string.background_show_settings)).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun unprotectedDashboardOffersCheckThisPhone() {
        var checks = 0
        compose.setContent { DashboardScreen(state = dashboard(mode = DisplayMode.SETUP_REQUIRED), onParentOptions = {}, onCheckPhone = { checks++ }) }
        compose.onNodeWithText(text(R.string.compat_check)).performScrollTo().performClick()
        assertEquals(1, checks)
    }

    @Test
    fun protectedDashboardHasNoCheckButtonOrBatteryNotice() {
        compose.setContent { DashboardScreen(state = dashboard(), onParentOptions = {}) }
        compose.onAllNodesWithText(text(R.string.compat_check)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.background_stopped)).assertCountEquals(0)
    }

    @Test
    fun bypassAppsScreenListsAppsAndReportsChoices() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            BypassAppsScreen(
                apps = listOf(LaunchableApp("com.example.homework", "Homework"), LaunchableApp("com.example.maps", "Maps")),
                selected = setOf("com.example.maps"),
                saveFailed = false,
                onToggle = { pkg, skip -> toggles += pkg to skip },
                onDone = {},
            )
        }
        compose.onNodeWithText(text(R.string.bypass_body)).assertIsDisplayed()
        compose.onNodeWithText("Homework").performScrollTo().performClick()
        compose.onNodeWithText("Maps").performScrollTo().performClick()
        assertEquals(listOf("com.example.homework" to true, "com.example.maps" to false), toggles)
    }

    @Test
    fun parentOptionsShowGraceLeftOrTimeToTheNextGrace() {
        val left = androidx.compose.runtime.mutableStateOf<Long?>(150_000L)
        compose.setContent {
            com.kidsgarden.app.ui.dashboard.ParentActionScreen(
                protectionReady = true, onUseAsParent = {}, onGiveToChild = {}, onSettings = {}, onVpnSetup = {},
                graceLeftMillis = left.value, nextGraceInMillis = 1_800_000L,
            )
        }
        compose.onNodeWithText(context.getString(R.string.grace_remaining, "2:30")).assertIsDisplayed()
        compose.runOnIdle { left.value = 0L }
        compose.onNodeWithText(context.getString(R.string.grace_next_in, "30:00")).assertIsDisplayed()
    }
}
