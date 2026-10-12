package com.kidsgarden.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.R
import com.kidsgarden.app.ui.navigation.Routes
import com.kidsgarden.app.ui.navigation.closeProtectedRoutes
import com.kidsgarden.app.ui.navigation.guardedComposable
import com.kidsgarden.app.ui.session.ChildDurationScreen
import com.kidsgarden.app.ui.session.ChildSessionViewModel
import com.kidsgarden.app.ui.session.ChildStartedScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Child handoff (T068). */
@RunWith(AndroidJUnit4::class)
class ChildSessionFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun minutes(n: Int) = context.resources.getQuantityString(R.plurals.child_duration_minutes, n, n)

    @Test
    fun arrowsStepTenMinutesBetweenTenAndOneHundredTwenty() {
        var chosen: Int? = null
        compose.setContent {
            ChildDurationScreen(state = ChildSessionViewModel.State.Choosing, onStart = { chosen = it })
        }
        val up = compose.onNodeWithContentDescription(context.resources.getQuantityString(R.plurals.child_duration_increase, 10, 10))
        val down = compose.onNodeWithContentDescription(context.resources.getQuantityString(R.plurals.child_duration_decrease, 10, 10))
        compose.onNodeWithText(minutes(10)).assertIsDisplayed()
        down.assertIsNotEnabled()
        up.performClick()
        compose.onNodeWithText(minutes(20)).assertIsDisplayed()
        repeat(10) { up.performClick() }
        compose.onNodeWithText(minutes(120)).assertIsDisplayed()
        up.assertIsNotEnabled()
        down.performClick()
        compose.onNodeWithText(minutes(110)).assertIsDisplayed()
        assertEquals(null, chosen)
        compose.onNodeWithText(context.getString(R.string.child_duration_start)).performClick()
        assertEquals(110, chosen)
    }

    @Test
    fun failedStartShowsCalmParentMessage() {
        compose.setContent {
            ChildDurationScreen(state = ChildSessionViewModel.State.Failed, onStart = {})
        }
        compose.onNodeWithText(context.getString(R.string.child_start_failed)).assertIsDisplayed()
    }

    @Test
    fun confirmationIsInertWithNoCountdownOrWarning() {
        compose.setContent { ChildStartedScreen() }
        compose.onNodeWithText(context.getString(R.string.child_started)).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodesWithText("minute", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    /**
     * After a child start, parent access is gone. Back, recents restore, a notification tap,
     * or a deep link all land on a protected route, and the guard sends each to the PIN.
     */
    @Test
    fun everyWayBackIntoProtectedScreensReachesThePin() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = Routes.PIN) {
                composable(Routes.PIN) { Text("pin-marker") }
                guardedComposable(Routes.CHILD_DURATION, { false }, { nav.closeProtectedRoutes() }) {
                    Text("protected-marker")
                }
                guardedComposable(Routes.ACTIONS, { false }, { nav.closeProtectedRoutes() }) {
                    Text("protected-marker")
                }
            }
        }
        for (route in listOf(Routes.CHILD_DURATION, Routes.ACTIONS)) {
            compose.runOnIdle { nav.navigate(route) }
            compose.waitForIdle()
            compose.onAllNodesWithText("protected-marker").assertCountEquals(0)
            compose.onNodeWithText("pin-marker").assertIsDisplayed()
            compose.runOnIdle { assertEquals(Routes.PIN, nav.currentDestination?.route) }
        }
    }
}
