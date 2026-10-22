package com.kidsgarden.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kidsgarden.app.domain.BoundaryScheduler
import com.kidsgarden.app.domain.CallObservation
import com.kidsgarden.app.domain.CallSourceState
import com.kidsgarden.app.domain.DomainEvent
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.NetworkPolicy
import com.kidsgarden.app.domain.NetworkPolicySink
import com.kidsgarden.app.domain.SessionManager
import com.kidsgarden.app.domain.TimeProvider
import com.kidsgarden.app.domain.VpnCapability
import com.kidsgarden.app.security.ParentPinManager
import com.kidsgarden.app.security.PinHasher
import com.kidsgarden.app.security.PinOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Storage privacy (T085). After setup, grants, calls, wrong PINs, and recovery, the settings
 * file holds only allowlisted parent-settings keys, and the app keeps no other product
 * files. The real file location is checked separately from the exercised copy.
 */
@RunWith(AndroidJUnit4::class)
class StoragePrivacyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.cacheDir, "storage-privacy-${System.nanoTime()}").apply { mkdirs() }
    private val file = File(dir, DataStoreProvider.FILE_NAME)

    @Volatile
    private var elapsed = 1_000_000L
    private val time = object : TimeProvider {
        override fun elapsedRealtimeMillis() = elapsed
        override fun epochMillis() = elapsed
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val allowedKeys = setOf(
        "schemaVersion", "pinCredential", "credentialVersion", "algorithm", "iterations",
        "derivedKeyLengthBytes", "salt", "hash", "dullIntensity", "vpnDullingEnabled",
        "vpnDullingConfiguration", "GENTLE", "MEDIUM", "STRONG", "bandwidthKbps",
        "addedRttLatencyMs", "softLandingDurationSeconds", "setup", "pinCreated", "onboardingComplete",
        // Schema v2 (FR-031, FR-034–036): parent choices and the parent's own brightness only.
        "active", "screenEffects", "greyScreenEnabled", "lowerVolumeEnabled", "lowerBrightnessEnabled",
        "buildUpSeconds", "breakSeconds", "brightnessRestore", "level", "automatic",
    )

    private fun keys(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> element.keys + element.values.flatMap { keys(it) }
        is JsonArray -> element.flatMap { keys(it) }.toSet()
        else -> emptySet()
    }

    @Test
    fun settingsFileHoldsOnlyAllowlistedParentSettings() = runBlocking {
        val repository = SettingsRepository(DataStoreProvider.createForFile(file, scope))
        val pins = ParentPinManager(repository, PinHasher(), time)

        // Setup and settings changes.
        assertEquals(PinOutcome.Accepted, pins.enroll("135790".toCharArray(), "135790".toCharArray()))
        repository.updateIntensity(DullIntensity.STRONG).getOrThrow()
        repository.updateSoftLandingDuration(60).getOrThrow()
        repository.updateVpnPreference(true).getOrThrow()
        repository.updateSetupFlags(SetupFlags(pinCreated = true, onboardingComplete = true)).getOrThrow()
        repository.updateMethodSwitches(network = true, grey = true, volume = true, brightness = true).getOrThrow()
        repository.updateEffectTiming(buildUpSeconds = 60, breakSeconds = 0).getOrThrow()
        repository.saveBrightnessRestore(BrightnessRestore(level = 120, automatic = true)).getOrThrow()
        repository.saveBrightnessRestore(null).getOrThrow()
        // Deactivate and Activate write only the `active` flag (T109).
        repository.updateActive(false).getOrThrow()
        repository.updateActive(true).getOrThrow()

        // Wrong PINs, including enough to trigger the retry delay.
        repeat(6) { pins.verify("000000".toCharArray()) }
        elapsed += 60_000
        assertEquals(PinOutcome.Accepted, pins.verify("135790".toCharArray()))
        assertEquals(PinOutcome.Accepted, pins.changePin("135790".toCharArray(), "246801".toCharArray(), "246801".toCharArray()))

        val afterSettings = file.readBytes()

        // Grants, a call, Soft Landing, expiry, and recovery: none of this may touch storage.
        val session = SessionManager(
            time = time,
            policySink = object : NetworkPolicySink {
                override suspend fun apply(policy: NetworkPolicy, generation: Long, validUntilElapsedMillis: Long?) = Unit
            },
            boundaryScheduler = object : BoundaryScheduler {
                override fun schedule(atElapsedMillis: Long, generation: Long) = Unit
                override fun cancel() = Unit
            },
            scope = scope,
        )
        session.dispatch(DomainEvent.SettingsChanged(repository.load().getOrThrow().toDomainSettings()))
        session.dispatch(DomainEvent.InteractivityObserved(true))
        session.dispatch(DomainEvent.VpnCapabilityChanged(VpnCapability.RUNNING))
        session.dispatch(DomainEvent.PinVerified)
        delay(200)
        session.state.value.grantPermission?.let { session.dispatch(DomainEvent.StartChildRequested(it, 10)) }
        session.dispatch(DomainEvent.CallAggregateChanged(CallObservation(CallSourceState.ACTIVE, 1, elapsed)))
        elapsed += 5 * 60_000
        session.dispatch(DomainEvent.CallAggregateChanged(CallObservation(CallSourceState.INACTIVE, 1, elapsed)))
        elapsed += 11 * 60_000
        session.dispatch(DomainEvent.DeadlineObserved(0))
        session.dispatch(DomainEvent.ProcessStarted())
        delay(200)

        assertArrayEquals("session activity must never write settings", afterSettings, file.readBytes())

        val content = file.readText()
        val unexpected = keys(Json.parseToJsonElement(content)) - allowedKeys
        assertTrue("unexpected keys: $unexpected", unexpected.isEmpty())
        assertFalse("raw PIN stored", content.contains("135790") || content.contains("246801"))
        for (forbidden in listOf("mode", "session", "grant", "pause", "call", "token", "http", "remaining")) {
            assertFalse("found '$forbidden'", content.contains(forbidden, ignoreCase = true))
        }

        // The exercised directory contains only the settings file (DataStore may leave a temp).
        val others = dir.listFiles().orEmpty().map { it.name }.filterNot { it.startsWith(DataStoreProvider.FILE_NAME) }
        assertTrue("extra files: $others", others.isEmpty())
    }

    @Test
    fun appKeepsNoOtherProductFiles() {
        val noBackup = context.noBackupFilesDir.listFiles().orEmpty()
            .map { it.name }
            .filterNot { it.startsWith(DataStoreProvider.FILE_NAME) }
        assertTrue("unexpected no-backup files: $noBackup", noBackup.isEmpty())

        // Framework files (for example the profile installer) are allowed; product files are not.
        val files = context.filesDir.listFiles().orEmpty()
            .map { it.name }
            .filterNot { it.startsWith("profileinstaller") || it.startsWith("profileInstalled") }
        assertTrue("unexpected files: $files", files.isEmpty())

        val databases = context.getDatabasePath("x").parentFile?.listFiles().orEmpty()
        assertTrue("databases found: ${databases.map { it.name }}", databases.isEmpty())
    }
}
