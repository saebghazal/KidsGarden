package com.kidsgarden.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPolicyTest {

    private val ready = SetupInputs(
        credentialPresent = true,
        onboardingComplete = true,
        vpnConsentGranted = true,
        forwardingHealthy = true,
        notificationsAllowed = true,
        storageAvailable = true,
    )

    @Test
    fun allInputsPresentIsComplete() {
        val status = SetupPolicy.evaluate(ready)
        assertTrue(status.complete)
        assertEquals(SetupStep.DONE, SetupPolicy.resumeStep(status))
    }

    @Test
    fun deniedNotificationsAreALimitationNotABlocker() {
        val status = SetupPolicy.evaluate(ready.copy(notificationsAllowed = false))
        assertTrue(status.complete)
        assertEquals(setOf(SetupLimitation.NOTIFICATIONS_DENIED), status.limitations)
    }

    @Test
    fun consentWithoutForwardingIsNotComplete() {
        val status = SetupPolicy.evaluate(ready.copy(forwardingHealthy = false))
        assertFalse(status.complete)
        assertEquals(setOf(SetupBlocker.FORWARDING_UNAVAILABLE), status.blockers)
    }

    @Test
    fun interruptedSetupResumesAtFirstIncompleteStep() {
        val noPin = SetupPolicy.evaluate(ready.copy(credentialPresent = false, onboardingComplete = false))
        assertEquals(SetupStep.WELCOME, SetupPolicy.resumeStep(noPin))

        val pinOnly = SetupPolicy.evaluate(ready.copy(onboardingComplete = false, vpnConsentGranted = false))
        assertEquals(SetupStep.NOTIFICATIONS, SetupPolicy.resumeStep(pinOnly))
    }

    @Test
    fun protectionNeverActiveWithoutRealForwarding() {
        val status = SetupPolicy.evaluate(ready.copy(forwardingHealthy = false))
        val protection = ProtectionStatusMapper.map(AuthorizationMode.DULL, VpnCapability.STARTING, status)
        assertFalse(protection.shapingActive)
        assertEquals(DisplayMode.SETUP_REQUIRED, protection.displayMode)
        assertEquals(SetupRemedy.START_VPN, protection.remedy)
    }

    @Test
    fun protectionRemedyFollowsFixOrder() {
        val status = SetupPolicy.evaluate(
            ready.copy(credentialPresent = false, vpnConsentGranted = false, forwardingHealthy = false),
        )
        val protection = ProtectionStatusMapper.map(AuthorizationMode.DULL, VpnCapability.UNAVAILABLE, status)
        assertEquals(SetupRemedy.CREATE_PIN, protection.remedy)
    }

    @Test
    fun healthySetupShowsDullWithNoRemedy() {
        val protection = ProtectionStatusMapper.map(
            AuthorizationMode.DULL,
            VpnCapability.RUNNING,
            SetupPolicy.evaluate(ready),
        )
        assertEquals(DisplayMode.DULL, protection.displayMode)
        assertTrue(protection.shapingActive)
        assertNull(protection.remedy)
    }
}
