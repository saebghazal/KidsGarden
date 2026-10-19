package com.kidsgarden.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.ui.dashboard.ParentActionScreen
import com.kidsgarden.app.ui.settings.SettingsScreen
import com.kidsgarden.app.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Active/Deactivated switch (T107, FR-031). Assertions use strings.xml, never literals. */
@RunWith(AndroidJUnit4::class)
class ActivationFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun settings(active: Boolean = true, saveFailed: Boolean = false) =
        SettingsUiState(DullIntensity.MEDIUM, 120, vpnRunning = true, callPausePermissionMissing = false, saveFailed = saveFailed, active = active)

    private fun setSettings(state: SettingsUiState, onDeactivate: () -> Unit = {}, onActivate: () -> Unit = {}) {
        compose.setContent {
            SettingsScreen(
                state = state,
                onIntensity = {}, onSoftLanding = {}, onStopVpn = {}, onStartVpn = {},
                onChangePin = {}, onLimitations = {}, onPrivacy = {}, onEnableCallPause = {},
                onDeactivate = onDeactivate,
                onActivate = onActivate,
            )
        }
    }

    @Test
    fun deactivateAsksOnceAndConfirms() {
        var deactivations = 0
        setSettings(settings(), onDeactivate = { deactivations++ })

        compose.onNodeWithText(text(R.string.active_switch_label)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.deactivate_confirm)).assertIsDisplayed()
        assertEquals(0, deactivations)

        compose.onNodeWithText(text(R.string.deactivate_confirm_action)).performClick()
        assertEquals(1, deactivations)
        compose.onAllNodesWithText(text(R.string.deactivate_confirm)).assertCountEquals(0)
    }

    @Test
    fun cancelLeavesKidsGardenOn() {
        var deactivations = 0
        setSettings(settings(), onDeactivate = { deactivations++ })

        compose.onNodeWithText(text(R.string.active_switch_label)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertEquals(0, deactivations)
        compose.onNodeWithText(text(R.string.active_switch_label)).assertIsOn()
    }

    @Test
    fun failedSaveShowsStorageMessageAndSwitchStaysOn() {
        setSettings(settings(active = true, saveFailed = true))
        compose.onNodeWithText(text(R.string.settings_save_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.active_switch_label)).performScrollTo().assertIsOn()
    }

    @Test
    fun deactivatedSwitchIsOffAndTurningOnGoesToPin() {
        var activations = 0
        setSettings(settings(active = false), onActivate = { activations++ })

        compose.onNodeWithText(text(R.string.active_switch_label)).performScrollTo().assertIsOff()
        compose.onNodeWithText(text(R.string.deactivated_status)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.active_switch_label)).performClick()

        assertEquals(1, activations)
        compose.onAllNodesWithText(text(R.string.deactivate_confirm)).assertCountEquals(0)
    }

    @Test
    fun actionsWhileDeactivatedOfferOnlyActivateAndSettings() {
        var activations = 0
        var settingsOpened = 0
        compose.setContent {
            ParentActionScreen(
                protectionReady = true,
                onUseAsParent = {},
                onGiveToChild = {},
                onSettings = { settingsOpened++ },
                onVpnSetup = {},
                onStatus = {},
                onActivate = { activations++ },
            )
        }
        compose.onNodeWithText(text(R.string.deactivated_status)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.action_use_as_parent)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.action_give_to_child)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.action_status)).assertCountEquals(0)

        compose.onNodeWithText(text(R.string.action_activate)).performClick()
        compose.onNodeWithText(text(R.string.action_settings)).performClick()
        assertEquals(1, activations)
        assertEquals(1, settingsOpened)
    }

    @Test
    fun failedActivateIsReported() {
        compose.setContent {
            ParentActionScreen(
                protectionReady = true, onUseAsParent = {}, onGiveToChild = {}, onSettings = {}, onVpnSetup = {},
                onActivate = {}, activateFailed = true,
            )
        }
        compose.onNodeWithText(text(R.string.activate_failed)).assertIsDisplayed()
    }

    @Test
    fun switchHasAccessibleLabelAndRole() {
        setSettings(settings())
        val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onNodeWithText(text(R.string.active_switch_label)).performScrollTo().assert(switchRole)
    }
}
