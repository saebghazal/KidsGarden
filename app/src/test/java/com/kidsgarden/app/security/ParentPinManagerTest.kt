package com.kidsgarden.app.security

import com.kidsgarden.app.data.SettingsRepository
import com.kidsgarden.app.data.TestSettingsStores
import com.kidsgarden.app.domain.TimeProvider
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
class ParentPinManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Clock(var elapsed: Long = 10_000L) : TimeProvider {
        override fun elapsedRealtimeMillis() = elapsed
        override fun epochMillis() = 0L
    }

    private val clock = Clock()

    private fun TestScope.setup(name: String = "settings.json"): Pair<ParentPinManager, File> {
        val file = File(tmp.root, name)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val repo = SettingsRepository(TestSettingsStores.forFile(file, scope))
        return ParentPinManager(repo, PinHasher(), clock, hashDispatcher = dispatcher) to file
    }

    private fun pin(s: String) = s.toCharArray()

    @Test
    fun enrollThenVerify() = runTest {
        val (manager, _) = setup()
        assertEquals(PinOutcome.Accepted, manager.enroll(pin("123456"), pin("123456")))
        assertEquals(PinOutcome.Accepted, manager.verify(pin("123456")))
        assertEquals(PinOutcome.Mismatch, manager.verify(pin("000000")))
    }

    @Test
    fun formatIsOneToSixDigits() = runTest {
        val (manager, _) = setup()
        assertEquals(PinOutcome.InvalidFormat, manager.enroll(pin(""), pin("")))
        assertEquals(PinOutcome.InvalidFormat, manager.enroll(pin("1234567"), pin("1234567")))
        assertEquals(PinOutcome.InvalidFormat, manager.enroll(pin("12345a"), pin("12345a")))
        assertEquals(PinOutcome.InvalidFormat, manager.enroll(pin("123456"), pin("123457")))
    }

    @Test
    fun everyLengthFromOneToSixIsAccepted() = runTest {
        for (candidate in listOf("7", "12", "123", "1234", "12345", "123456")) {
            val (manager, _) = setup("settings-$candidate.json")
            assertEquals(candidate, PinOutcome.Accepted, manager.enroll(pin(candidate), pin(candidate)))
            assertEquals(candidate, PinOutcome.Accepted, manager.verify(pin(candidate)))
            assertEquals(candidate, PinOutcome.Mismatch, manager.verify(pin("0")))
        }
    }

    @Test
    fun secondEnrollmentIsRefused() = runTest {
        val (manager, _) = setup()
        manager.enroll(pin("123456"), pin("123456"))
        assertEquals(PinOutcome.AlreadyEnrolled, manager.enroll(pin("654321"), pin("654321")))
    }

    @Test
    fun fiveFailuresImposeThirtySecondDelayThenReset() = runTest {
        val (manager, _) = setup()
        manager.enroll(pin("123456"), pin("123456"))
        repeat(5) { assertEquals(PinOutcome.Mismatch, manager.verify(pin("000000"))) }

        // Even the correct PIN is not checked during the delay.
        assertTrue(manager.verify(pin("123456")) is PinOutcome.RetryDelayed)

        clock.elapsed += 30_000L
        assertEquals(PinOutcome.Accepted, manager.verify(pin("123456")))
    }

    @Test
    fun inputArraysAreCleared() = runTest {
        val (manager, _) = setup()
        val p = pin("123456")
        val c = pin("123456")
        manager.enroll(p, c)
        assertTrue(p.all { it == '\u0000' })
        assertTrue(c.all { it == '\u0000' })
    }

    @Test
    fun changePinRequiresCurrentAndReplacesCredential() = runTest {
        val (manager, _) = setup()
        manager.enroll(pin("123456"), pin("123456"))

        assertEquals(PinOutcome.Mismatch, manager.changePin(pin("999999"), pin("222222"), pin("222222")))
        assertEquals(PinOutcome.Accepted, manager.changePin(pin("123456"), pin("222222"), pin("222222")))
        assertEquals(PinOutcome.Mismatch, manager.verify(pin("123456")))
        assertEquals(PinOutcome.Accepted, manager.verify(pin("222222")))
    }

    @Test
    fun rawPinNeverReachesDisk() = runTest {
        val (manager, file) = setup()
        manager.enroll(pin("123456"), pin("123456"))
        assertFalse(file.readText().contains("123456"))
    }

    @Test
    fun legacyCredentialIsUpgradedOnTheNextCorrectPin() = runTest {
        val file = File(tmp.root, "legacy.json")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = SettingsRepository(TestSettingsStores.forFile(file, CoroutineScope(backgroundScope.coroutineContext + dispatcher)))
        repo.createParentCredential(LegacyCredentials.v1("2580")).getOrThrow()
        val manager = ParentPinManager(repo, PinHasher(), clock, hashDispatcher = dispatcher)

        // A wrong PIN never upgrades.
        assertEquals(PinOutcome.Mismatch, manager.verify(pin("1111")))
        assertEquals(1, repo.load().getOrThrow().pinCredential!!.credentialVersion)

        assertEquals(PinOutcome.Accepted, manager.verify(pin("2580")))
        val upgraded = repo.load().getOrThrow().pinCredential!!
        assertEquals(2, upgraded.credentialVersion)
        assertEquals(180_000, upgraded.iterations)

        // Same PIN still works after the upgrade; the old one is gone.
        assertEquals(PinOutcome.Accepted, manager.verify(pin("2580")))
        assertEquals(PinOutcome.Mismatch, manager.verify(pin("2581")))
    }
}
