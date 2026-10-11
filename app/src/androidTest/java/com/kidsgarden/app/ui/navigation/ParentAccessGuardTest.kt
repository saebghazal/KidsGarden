package com.kidsgarden.app.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Foundational guard behavior (T030). Once story routes exist, this suite is re-run
 * against every route registered through guardedComposable.
 */
@RunWith(AndroidJUnit4::class)
class ParentAccessGuardTest {

    @get:Rule
    val compose = createComposeRule()

    // Test-only marker text; never shipped in product UI.
    private val protectedText = "protected-content-marker"

    @Test
    fun protectedContentHiddenAndDeniedWithoutAccess() {
        var denied = 0
        compose.setContent {
            ParentAccessGuard(parentAccessActive = false, onDenied = { denied++ }) {
                Text(protectedText)
            }
        }
        compose.onNodeWithText(protectedText).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, denied) }
    }

    @Test
    fun protectedContentShownWithAccess() {
        compose.setContent {
            ParentAccessGuard(parentAccessActive = true, onDenied = {}) {
                Text(protectedText)
            }
        }
        compose.onNodeWithText(protectedText).assertIsDisplayed()
    }

    @Test
    fun losingAccessHidesContentImmediately() {
        val access = mutableStateOf(true)
        var denied = 0
        compose.setContent {
            ParentAccessGuard(parentAccessActive = access.value, onDenied = { denied++ }) {
                Text(protectedText)
            }
        }
        compose.onNodeWithText(protectedText).assertIsDisplayed()

        compose.runOnIdle { access.value = false }

        compose.onNodeWithText(protectedText).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, denied) }
    }
}
