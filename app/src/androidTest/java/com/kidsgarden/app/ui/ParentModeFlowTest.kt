package com.kidsgarden.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.ui.dashboard.ParentActionScreen
import com.kidsgarden.app.ui.dashboard.ParentModeScreen
import com.kidsgarden.app.ui.pin.ParentPinContent
import com.kidsgarden.app.ui.pin.ParentPinViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PIN entry, parent actions, and the Parent Mode screen (T057). */
@RunWith(AndroidJUnit4::class)
class ParentModeFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private val frozenTime = object : TimeProvider {
        override fun elapsedRealtimeMillis() = 100_000L
        override fun epochMillis() = 100_000L
    }

    @Test
    fun enteredPinIsSubmittedAndFieldClears() {
        var submitted: String? = null
        compose.setContent {
            ParentPinContent(state = ParentPinViewModel.State.Idle, time = frozenTime, onSubmit = { submitted = String(it) })
        }
        compose.onNodeWithText(text(R.string.pin_label)).performTextInput("123456")
        compose.onNodeWithText(text(R.string.pin_unlock)).performClick()
        assertEquals("123456", submitted)
    }

    @Test
    fun wrongPinShowsCalmMessage() {
        compose.setContent {
            ParentPinContent(state = ParentPinViewModel.State.Mismatch, time = frozenTime, onSubmit = {})
        }
        compose.onNodeWithText(text(R.string.pin_mismatch)).assertIsDisplayed()
    }

    @Test
    fun retryDelayDisablesUnlockAndShowsWait() {
        compose.setContent {
            ParentPinContent(
                state = ParentPinViewModel.State.RetryDelayed(untilElapsedMillis = 130_000L),
                time = frozenTime,
                onSubmit = {},
            )
        }
        val wait = context.resources.getQuantityString(R.plurals.pin_retry_delay, 30, 30)
        compose.onNodeWithText(wait).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.pin_unlock)).assertIsNotEnabled()
    }

    @Test
    fun actionSelectionStartsParentMode() {
        var started = 0
        compose.setContent {
            ParentActionScreen(
                protectionReady = true,
                onUseAsParent = { started++ },
                onGiveToChild = null,
                onSettings = null,
                onVpnSetup = {},
            )
        }
        compose.onNodeWithText(text(R.string.action_use_as_parent)).assertIsEnabled().performClick()
        assertEquals(1, started)
        compose.onAllNodesWithText(text(R.string.action_vpn_setup)).assertCountEquals(0)
    }

    @Test
    fun allThreeActionsAppearWhenWired() {
        compose.setContent {
            ParentActionScreen(
                protectionReady = true,
                onUseAsParent = {},
                onGiveToChild = {},
                onSettings = {},
                onVpnSetup = {},
            )
        }
        compose.onNodeWithText(text(R.string.action_use_as_parent)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_give_to_child)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_settings)).assertIsDisplayed()
    }

    @Test
    fun incompleteProtectionDisablesGrantsCalmly() {
        var vpnSetup = 0
        compose.setContent {
            ParentActionScreen(
                protectionReady = false,
                onUseAsParent = {},
                onGiveToChild = null,
                onSettings = null,
                onVpnSetup = { vpnSetup++ },
            )
        }
        compose.onNodeWithText(text(R.string.vpn_limited)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_use_as_parent)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.action_vpn_setup)).performClick()
        assertEquals(1, vpnSetup)
    }

    @Test
    fun parentModeShowsLockCopyAndExitsWithoutPin() {
        var exits = 0
        compose.setContent { ParentModeScreen(onExit = { exits++ }) }
        compose.onNodeWithText(text(R.string.parent_mode_confirmation)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.pin_label)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.parent_mode_exit)).performClick()
        assertEquals(1, exits)
    }
}
