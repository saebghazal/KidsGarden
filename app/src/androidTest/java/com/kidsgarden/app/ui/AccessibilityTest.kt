package com.kidsgarden.app.ui

import android.app.Notification
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.ui.dashboard.ParentActionScreen
import com.kidsgarden.app.ui.pin.ParentPinContent
import com.kidsgarden.app.ui.pin.ParentPinViewModel
import com.kidsgarden.app.ui.setup.VpnSetupScreen
import com.kidsgarden.app.ui.setup.VpnSetupStatus
import com.kidsgarden.app.vpn.StatusNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility (T087): TalkBack labels, focus order, 200% font scale on the PIN and action
 * screens, and a lock-screen-safe notification.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private val time = object : TimeProvider {
        override fun elapsedRealtimeMillis() = 0L
        override fun epochMillis() = 0L
    }

    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f), content = content)
    }

    private fun top(matcher: SemanticsMatcher): Float =
        compose.onNode(matcher, useUnmergedTree = false).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun pinScreenHasHeadingLabelledFieldAndReadingOrder() {
        compose.setContent { ParentPinContent(state = ParentPinViewModel.State.Idle, time = time, onSubmit = {}) }

        val heading = isHeading() and hasText(text(R.string.pin_entry_title))
        val field = hasSetTextAction() and hasText(text(R.string.pin_label))
        val button = hasText(text(R.string.pin_unlock))
        compose.onNode(heading).assertIsDisplayed()
        compose.onNode(field).assertIsDisplayed()

        // TalkBack follows layout order: title, then the field, then the action.
        assertTrue(top(heading) < top(field))
        assertTrue(top(field) < top(button))
    }

    @Test
    fun pinScreenWorksAtDoubleFontSize() {
        compose.setContent {
            LargeFont { ParentPinContent(state = ParentPinViewModel.State.Mismatch, time = time, onSubmit = {}) }
        }
        compose.onNodeWithText(text(R.string.pin_mismatch)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.pin_unlock)).performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun actionScreenWorksAtDoubleFontSizeWithEveryActionReachable() {
        compose.setContent {
            LargeFont {
                ParentActionScreen(
                    protectionReady = true,
                    onUseAsParent = {},
                    onGiveToChild = {},
                    onSettings = {},
                    onVpnSetup = {},
                    onStatus = {},
                )
            }
        }
        compose.onNode(isHeading() and hasText(text(R.string.actions_title))).assertIsDisplayed()
        for (id in listOf(R.string.action_use_as_parent, R.string.action_give_to_child, R.string.action_settings, R.string.action_status)) {
            compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun errorMessagesAreAnnounced() {
        compose.setContent { ParentPinContent(state = ParentPinViewModel.State.Mismatch, time = time, onSubmit = {}) }
        compose.onNode(hasText(text(R.string.pin_mismatch)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .assertIsDisplayed()
    }

    @Test
    fun progressIndicatorIsLabelled() {
        compose.setContent { VpnSetupScreen(status = VpnSetupStatus.STARTING, onSetUp = {}, onFinish = {}) }
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text(R.string.vpn_status_starting))),
        ).assertExists()
    }

    @Test
    fun lockScreenNotificationShowsNoPinOrSessionDetails() {
        val notification = StatusNotification.build(context, null)
        val extras = notification.extras
        assertEquals(text(R.string.notification_running), extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertNull(extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull(notification.actions)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        val shown = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        for (forbidden in listOf("PIN", "minute", "play", "child", "dull")) {
            assertTrue("notification mentions '$forbidden'", !shown.contains(forbidden, ignoreCase = true))
        }
    }
}
