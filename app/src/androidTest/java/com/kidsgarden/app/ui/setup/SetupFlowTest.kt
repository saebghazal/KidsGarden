package com.kidsgarden.app.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.R
import com.kidsgarden.app.domain.AuthorizationMode
import com.kidsgarden.app.domain.BoundaryScheduler
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.domain.NetworkPolicy
import com.kidsgarden.app.domain.NetworkPolicySink
import com.kidsgarden.app.domain.SessionManager
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.domain.VpnCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup screens with fake platform adapters (T049). Real VPN consent and lifecycle are
 * covered by the device matrix, not here.
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun sessionManager(): SessionManager = SessionManager(
        time = object : TimeProvider {
            override fun elapsedRealtimeMillis() = 1_000L
            override fun epochMillis() = 1_000L
        },
        policySink = object : NetworkPolicySink {
            override suspend fun apply(policy: NetworkPolicy, generation: Long) = Unit
        },
        boundaryScheduler = object : BoundaryScheduler {
            override fun schedule(atElapsedMillis: Long, generation: Long) = Unit
            override fun cancel() = Unit
        },
        scope = scope,
    )

    private enum class Step { WELCOME, PRIVACY, PIN, NOTIFICATIONS, VPN, DONE }

    @Test
    fun fullFlowWithFakesEndsDullWithNoGrant() {
        val session = sessionManager()
        session.dispatch(DomainEvent.ProcessStarted)
        var notificationPrompts = 0
        var vpnPrompts = 0

        compose.setContent {
            var step by remember { mutableStateOf(Step.WELCOME) }
            var vpnStatus by remember { mutableStateOf(VpnSetupStatus.NOT_STARTED) }
            when (step) {
                Step.WELCOME -> WelcomeScreen(onContinue = { step = Step.PRIVACY })
                Step.PRIVACY -> PrivacyExplanationScreen(onContinue = { step = Step.PIN })
                Step.PIN -> CreatePinContent(state = CreatePinViewModel.State.Idle, onSubmit = { _, _ ->
                    session.dispatch(DomainEvent.PinVerified)
                    step = Step.NOTIFICATIONS
                })
                Step.NOTIFICATIONS -> NotificationPermissionScreen(
                    allowed = null,
                    onAllow = { notificationPrompts++ },
                    onContinue = { step = Step.VPN },
                )
                Step.VPN -> VpnSetupScreen(
                    status = vpnStatus,
                    onSetUp = {
                        vpnPrompts++
                        session.dispatch(DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
                        vpnStatus = VpnSetupStatus.RUNNING
                    },
                    onFinish = { step = Step.DONE },
                )
                Step.DONE -> Unit
            }
        }

        compose.onNodeWithText(text(R.string.welcome_start)).performClick()
        compose.onNodeWithText(text(R.string.privacy_summary)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_continue)).performClick()
        compose.onNodeWithText(text(R.string.pin_label)).performTextInput("123456")
        compose.onNodeWithText(text(R.string.pin_confirm_label)).performTextInput("123456")
        compose.onNodeWithText(text(R.string.pin_save)).performClick()

        // The explanation is visible before any prompt; skipping it is allowed.
        compose.onNodeWithText(text(R.string.notifications_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_continue)).performClick()

        compose.onNodeWithText(text(R.string.vpn_body)).assertIsDisplayed()
        assertEquals(0, vpnPrompts)
        compose.onNodeWithText(text(R.string.vpn_set_up)).performClick()
        compose.onNodeWithText(text(R.string.vpn_status_running)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_finish)).performClick()

        compose.waitForIdle()
        assertEquals(0, notificationPrompts)
        assertEquals(1, vpnPrompts)
        compose.waitUntil(timeoutMillis = 5_000) { session.state.value.vpnCapability == VpnCapability.RUNNING }
        // Consent plus a running VPN is not a grant.
        assertEquals(AuthorizationMode.DULL, session.state.value.authorizationMode)
        assertEquals(null, session.state.value.remainingPlayMillis)
    }

    @Test
    fun explanationPrecedesVpnPrompt() {
        var prompts = 0
        compose.setContent {
            VpnSetupScreen(status = VpnSetupStatus.NOT_STARTED, onSetUp = { prompts++ }, onFinish = {})
        }
        compose.onNodeWithText(text(R.string.vpn_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_prompt_notice)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.vpn_limited)).assertIsDisplayed()
        assertEquals(0, prompts)
        compose.onNodeWithText(text(R.string.vpn_set_up)).performClick()
        assertEquals(1, prompts)
    }

    @Test
    fun vpnDenialIsCalmAndResumable() {
        var prompts = 0
        compose.setContent {
            VpnSetupScreen(status = VpnSetupStatus.DENIED, onSetUp = { prompts++ }, onFinish = {})
        }
        compose.onNodeWithText(text(R.string.vpn_status_denied)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.action_finish)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.action_try_again)).performClick()
        assertEquals(1, prompts)
    }

    @Test
    fun notificationExplanationPrecedesPromptAndDenialIsRespected() {
        var prompts = 0
        var continued = 0
        compose.setContent {
            NotificationPermissionScreen(allowed = false, onAllow = { prompts++ }, onContinue = { continued++ })
        }
        compose.onNodeWithText(text(R.string.notifications_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.notifications_denied)).assertIsDisplayed()
        assertEquals(0, prompts)
        compose.onNodeWithText(text(R.string.action_continue)).performClick()
        assertEquals(1, continued)
    }

    @Test
    fun pinMismatchMessageUsesResourceText() {
        compose.setContent {
            CreatePinContent(state = CreatePinViewModel.State.ConfirmationMismatch, onSubmit = { _, _ -> })
        }
        compose.onNodeWithText(text(R.string.pin_confirmation_mismatch)).assertIsDisplayed()
    }

    @Test
    fun startingNeverClaimsProtection() {
        compose.setContent {
            VpnSetupScreen(status = VpnSetupStatus.STARTING, onSetUp = {}, onFinish = {})
        }
        compose.onNodeWithText(text(R.string.vpn_status_starting)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.vpn_status_running)).assertCountEquals(0)
    }
}
