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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.ui.dashboard.ParentActionScreen
import com.kidsgarden.app.ui.pin.ParentPinContent
import com.kidsgarden.app.ui.pin.ParentPinViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Active/Deactivated switch at the top of Parent options (T107, FR-031, owner change
 * 2026-10-19). Assertions use strings.xml, never literals.
 */
@RunWith(AndroidJUnit4::class)
class ActivationFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private val changes = mutableListOf<Boolean>()

    private fun setActions(active: Boolean, failed: Boolean = false) {
        compose.setContent {
            ParentActionScreen(
                protectionReady = true,
                onUseAsParent = {},
                onGiveToChild = {},
                onSettings = {},
                onVpnSetup = {},
                onStatus = {},
                active = active,
                onActiveChange = { changes += it },
                switchFailed = failed,
            )
        }
    }

    @Test
    fun switchIsOnTopOfParentOptionsAndOn() {
        setActions(active = true)
        compose.onNodeWithText(text(R.string.active_switch_label)).assertIsDisplayed().assertIsOn()
        compose.onNodeWithText(text(R.string.active_status)).assertIsDisplayed()
    }

    @Test
    fun turningOffTakesEffectAtOnceWithoutPopUp() {
        setActions(active = true)
        compose.onNodeWithText(text(R.string.active_switch_label)).performClick()
        assertEquals(listOf(false), changes)
        compose.onAllNodesWithText(text(R.string.pin_entry_title)).assertCountEquals(0)
    }

    @Test
    fun turningOnTakesEffectAtOnceWithoutPin() {
        setActions(active = false)
        compose.onNodeWithText(text(R.string.active_switch_label)).assertIsOff().performClick()
        assertEquals(listOf(true), changes)
        compose.onAllNodesWithText(text(R.string.pin_entry_title)).assertCountEquals(0)
    }

    @Test
    fun whileOffTheStateIsObviousAndGrantsAreHidden() {
        setActions(active = false)
        compose.onNodeWithText(text(R.string.deactivated_status)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.action_use_as_parent)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.action_give_to_child)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.action_settings)).assertIsDisplayed()
    }

    @Test
    fun failedSaveIsReported() {
        setActions(active = true, failed = true)
        compose.onNodeWithText(text(R.string.settings_save_failed)).assertIsDisplayed()
    }

    @Test
    fun pinScreenShowsThatKidsGardenIsOff() {
        val time = object : TimeProvider {
            override fun elapsedRealtimeMillis() = 0L
            override fun epochMillis() = 0L
        }
        compose.setContent {
            ParentPinContent(state = ParentPinViewModel.State.Idle, time = time, onSubmit = {}, deactivated = true)
        }
        compose.onNodeWithText(text(R.string.deactivated_status)).assertIsDisplayed()
    }

    @Test
    fun switchHasAccessibleLabelAndRole() {
        setActions(active = true)
        val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onNodeWithText(text(R.string.active_switch_label)).assert(switchRole)
    }
}
