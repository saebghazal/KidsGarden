package com.kidsgarden.app.data

import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.security.PinHasher
import kotlinx.coroutines.CoroutineScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hasher = PinHasher()

    private fun TestScope.repository(file: File = File(tmp.root, "settings.json")): SettingsRepository {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return SettingsRepository(DataStoreProvider.createForFile(file, scope))
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
}
