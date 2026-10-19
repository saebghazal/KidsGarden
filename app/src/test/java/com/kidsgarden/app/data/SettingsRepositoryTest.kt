package com.kidsgarden.app.data

import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.security.PinHasher
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hasher = PinHasher()

    private fun TestScope.repository(file: File = File(tmp.root, "settings.json")): SettingsRepository {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return SettingsRepository(TestSettingsStores.forFile(file, scope))
    }

    @Test
    fun missingFileLoadsDefaults() = runTest {
        assertEquals(SettingsDocument(), repository().load().getOrThrow())
    }

    @Test
    fun updatesAreWrittenToFileWithoutAuthorizationFields() = runTest {
        val file = File(tmp.root, "persist.json")
        repository(file).updateIntensity(DullIntensity.STRONG).getOrThrow()
        val text = file.readText()
        assertTrue(text.contains("\"dullIntensity\":\"STRONG\""))
        assertFalse(text.contains("childSession"))
    }

    @Test
    fun invalidSoftLandingDurationIsRejected() = runTest {
        val result = repository().updateSoftLandingDuration(90)
        assertTrue(result.exceptionOrNull() is SettingsFailure.InvalidValue)
    }

    @Test
    fun createCredentialSetsPinCreated() = runTest {
        val repo = repository()
        val doc = repo.createParentCredential(hasher.create("123456".toCharArray())).getOrThrow()
        assertTrue(doc.setup.pinCreated)
    }

    @Test
    fun staleCredentialChangeIsRejectedAndOldCredentialKept() = runTest {
        val repo = repository()
        val original = hasher.create("123456".toCharArray())
        repo.createParentCredential(original).getOrThrow()

        val notTheStoredOne = hasher.create("654321".toCharArray())
        val result = repo.changeCredential(expected = notTheStoredOne, newCredential = hasher.create("111111".toCharArray()))

        assertTrue(result.exceptionOrNull() is SettingsFailure.StaleVersion)
        assertEquals(original, repo.load().getOrThrow().pinCredential)
    }

    @Test
    fun validCredentialChangeReplacesCredential() = runTest {
        val repo = repository()
        val original = hasher.create("123456".toCharArray())
        repo.createParentCredential(original).getOrThrow()
        val replacement = hasher.create("222222".toCharArray())

        repo.changeCredential(expected = original, newCredential = replacement).getOrThrow()

        assertEquals(replacement, repo.load().getOrThrow().pinCredential)
    }

    @Test
    fun setupFlagsMustAgreeWithCredential() = runTest {
        val result = repository().updateSetupFlags(SetupFlags(pinCreated = true, onboardingComplete = true))
        assertTrue(result.exceptionOrNull() is SettingsFailure.InvalidValue)
    }

    @Test
    fun corruptFileIsReplacedWithDefaults() = runTest {
        val file = File(tmp.root, "corrupt.json").apply { writeText("{garbage") }
        assertEquals(SettingsDocument(), repository(file).load().getOrThrow())
    }

    /** Reads fine, every write fails like a full or unavailable disk. */
    private class FailingStore(private val current: SettingsDocument) : DataStore<SettingsDocument> {
        override val data: Flow<SettingsDocument> = flowOf(current)
        override suspend fun updateData(transform: suspend (t: SettingsDocument) -> SettingsDocument): SettingsDocument =
            throw IOException("disk unavailable")
    }

    private val v1Text = """{"schemaVersion":1,"pinCredential":null,"dullIntensity":"GENTLE","vpnDullingEnabled":true,""" +
        """"vpnDullingConfiguration":{"GENTLE":{"bandwidthKbps":256,"addedRttLatencyMs":300},""" +
        """"MEDIUM":{"bandwidthKbps":128,"addedRttLatencyMs":600},"STRONG":{"bandwidthKbps":64,"addedRttLatencyMs":1000}},""" +
        """"softLandingDurationSeconds":120,"setup":{"pinCreated":false,"onboardingComplete":false}}"""

    @Test
    fun v1FileMigratesToV2WithActiveTrue() = runTest {
        val file = File(tmp.root, "v1.json").apply { writeText(v1Text) }
        val repo = repository(file)
        val doc = repo.load().getOrThrow()
        assertEquals(2, doc.schemaVersion)
        assertTrue(doc.active)
        assertEquals(DullIntensity.GENTLE, doc.dullIntensity)
        assertTrue(file.readText().contains("\"schemaVersion\":2"))
    }

    @Test
    fun defaultsAndCorruptionAreActive() = runTest {
        assertTrue(repository().load().getOrThrow().active)
        val file = File(tmp.root, "bad.json").apply { writeText("{\"schemaVersion\":2}") }
        assertTrue(repository(file).load().getOrThrow().active)
    }

    @Test
    fun updateActiveRoundTrip() = runTest {
        val file = File(tmp.root, "active.json")
        repository(file).updateActive(false).getOrThrow()
        assertTrue(file.readText().contains("\"active\":false"))
        assertFalse(SettingsDocumentSerializer.decode(file.readText()).active)
    }

    @Test
    fun failedWriteReportsIoAndKeepsPreviousValue() = runTest {
        val repo = SettingsRepository(FailingStore(SettingsDocument(active = true)))
        assertTrue(repo.updateActive(false).exceptionOrNull() is SettingsFailure.IoUnavailable)
        assertTrue(repo.load().getOrThrow().active)
    }

    @Test
    fun allMethodsOffIsRejected() = runTest {
        val repo = repository()
        val result = repo.updateMethodSwitches(network = false, grey = false, volume = false, brightness = false)
        assertTrue(result.exceptionOrNull() is SettingsFailure.InvalidValue)
        val doc = repo.updateMethodSwitches(network = false, grey = true, volume = false, brightness = true).getOrThrow()
        assertFalse(doc.vpnDullingEnabled)
        assertTrue(doc.screenEffects.greyScreenEnabled && doc.screenEffects.lowerBrightnessEnabled)
    }

    @Test
    fun networkOffAfterSetupNeedsAnotherMethod() = runTest {
        val repo = repository()
        repo.createParentCredential(hasher.create("1234".toCharArray())).getOrThrow()
        repo.updateVpnPreference(true).getOrThrow()
        repo.updateSetupFlags(SetupFlags(pinCreated = true, onboardingComplete = true)).getOrThrow()

        assertTrue(repo.updateVpnPreference(false).exceptionOrNull() is SettingsFailure.InvalidValue)
        repo.updateMethodSwitches(network = true, grey = false, volume = true, brightness = false).getOrThrow()
        assertFalse(repo.updateVpnPreference(false).getOrThrow().vpnDullingEnabled)
    }

    @Test
    fun effectTimingBounds() = runTest {
        val repo = repository()
        assertTrue(repo.updateEffectTiming(30, 60).exceptionOrNull() is SettingsFailure.InvalidValue)
        assertTrue(repo.updateEffectTiming(120, 45).exceptionOrNull() is SettingsFailure.InvalidValue)
        val doc = repo.updateEffectTiming(300, 0).getOrThrow()
        assertEquals(300, doc.screenEffects.buildUpSeconds)
        assertEquals(0, doc.screenEffects.breakSeconds)
    }

    @Test
    fun brightnessRestoreSetAndClear() = runTest {
        val repo = repository()
        val saved = BrightnessRestore(level = 200, automatic = true)
        assertEquals(saved, repo.saveBrightnessRestore(saved).getOrThrow().brightnessRestore)
        assertEquals(null, repo.saveBrightnessRestore(null).getOrThrow().brightnessRestore)
    }
}
