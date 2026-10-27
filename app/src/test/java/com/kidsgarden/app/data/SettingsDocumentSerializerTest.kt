package com.kidsgarden.app.data

import androidx.datastore.core.CorruptionException
import com.kidsgarden.app.domain.DullIntensity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SettingsDocumentSerializerTest {

    private val serializer = SettingsDocumentSerializer

    private fun assertCorrupt(text: String) {
        try {
            serializer.decode(text)
            throw AssertionError("expected CorruptionException")
        } catch (_: CorruptionException) {
            // expected
        }
    }

    @Test
    fun defaultsMatchDataModel() {
        val d = serializer.defaultValue
        assertEquals(4, d.schemaVersion)
        assertEquals(emptyList<String>(), d.bypassPackages)
        assertEquals(UnlockGraceSettings(enabled = true, graceSeconds = 300, fadeSeconds = 120, restSeconds = 3600), d.unlockGrace)
        assertEquals(null, d.lastUnlockGraceEpochMillis)
        assertTrue(d.active)
        assertEquals(ScreenEffects(false, false, false, buildUpSeconds = 120, breakSeconds = 60), d.screenEffects)
        assertEquals(null, d.brightnessRestore)
        assertEquals(null, d.pinCredential)
        assertEquals(DullIntensity.MEDIUM, d.dullIntensity)
        assertFalse(d.vpnDullingEnabled)
        assertEquals(120, d.softLandingDurationSeconds)
        assertEquals(ThrottleSettings(256, 300), d.vpnDullingConfiguration[DullIntensity.GENTLE])
        assertEquals(ThrottleSettings(128, 600), d.vpnDullingConfiguration[DullIntensity.MEDIUM])
        assertEquals(ThrottleSettings(64, 1000), d.vpnDullingConfiguration[DullIntensity.STRONG])
        assertEquals(SetupFlags(pinCreated = false, onboardingComplete = false), d.setup)
    }

    @Test
    fun roundTripPreservesDocument() {
        val doc = SettingsDocument(dullIntensity = DullIntensity.STRONG, softLandingDurationSeconds = 60)
        assertEquals(doc, serializer.decode(serializer.encode(doc)))
    }

    @Test
    fun writtenJsonNeverContainsAuthorizationFields() {
        val text = serializer.encode(SettingsDocument())
        for (forbidden in listOf("\"mode\"", "childSession", "pause", "token", "grant")) {
            assertFalse("found $forbidden", text.contains(forbidden))
        }
    }

    @Test
    fun legacyModeAndChildSessionKeysAreDropped() {
        val base = serializer.encode(SettingsDocument()).removeSuffix("}")
        val legacy = "$base,\"mode\":\"PARENT_MODE\",\"childSession\":{\"startedAtEpochMillis\":1}}"
        val decoded = serializer.decode(legacy)
        assertEquals(SettingsDocument(), decoded)
        assertFalse(serializer.encode(decoded).contains("childSession"))
    }

    @Test
    fun otherUnknownKeysAreCorruption() {
        val base = serializer.encode(SettingsDocument()).removeSuffix("}")
        assertCorrupt("$base,\"surprise\":true}")
    }

    @Test
    fun malformedJsonIsCorruption() {
        assertCorrupt("{not json")
        assertCorrupt("[]")
        assertCorrupt("")
    }

    @Test
    fun missingOrFutureSchemaIsCorruption() {
        assertCorrupt("{}")
        val future = serializer.encode(SettingsDocument()).replace("\"schemaVersion\":4", "\"schemaVersion\":5")
        assertCorrupt(future)
    }

    @Test
    fun outOfRangeValuesAreCorruption() {
        val text = serializer.encode(SettingsDocument())
        assertCorrupt(text.replace("\"softLandingDurationSeconds\":120", "\"softLandingDurationSeconds\":90"))
        assertCorrupt(text.replace("\"bandwidthKbps\":64", "\"bandwidthKbps\":0"))
        assertCorrupt(text.replace("\"dullIntensity\":\"MEDIUM\"", "\"dullIntensity\":\"EXTREME\""))
    }

    /** A v1 document as V1 wrote it: no active, screenEffects or brightnessRestore. */
    private val v1Fixture = """{"schemaVersion":1,"pinCredential":null,"dullIntensity":"STRONG","vpnDullingEnabled":true,""" +
        """"vpnDullingConfiguration":{"GENTLE":{"bandwidthKbps":256,"addedRttLatencyMs":300},""" +
        """"MEDIUM":{"bandwidthKbps":128,"addedRttLatencyMs":600},"STRONG":{"bandwidthKbps":64,"addedRttLatencyMs":1000}},""" +
        """"softLandingDurationSeconds":60,"setup":{"pinCreated":false,"onboardingComplete":true}}"""

    @Test
    fun v1DocumentDecodesWithV2DefaultsForMigration() {
        val decoded = serializer.decode(v1Fixture)
        assertEquals(1, decoded.schemaVersion)
        assertTrue(decoded.active)
        assertEquals(ScreenEffects(), decoded.screenEffects)
        assertEquals(null, decoded.brightnessRestore)
        assertEquals(DullIntensity.STRONG, decoded.dullIntensity)
        assertEquals(60, decoded.softLandingDurationSeconds)
    }

    @Test
    fun migrationReachesCurrentAndIsIdempotent() {
        val migrated = SettingsMigrations.migrate(serializer.decode(v1Fixture))
        assertEquals(4, migrated.schemaVersion)
        assertTrue(migrated.unlockGrace.enabled)
        assertTrue(migrated.active)
        assertEquals(migrated, SettingsMigrations.migrate(migrated))
        assertEquals(migrated, serializer.decode(serializer.encode(migrated)))
    }

    @Test
    fun v1WithV2KeysIsCorruption() {
        assertCorrupt(v1Fixture.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"active\":false,"))
    }

    @Test
    fun v2MissingActiveIsCorruption() {
        val text = serializer.encode(SettingsDocument())
        assertCorrupt(text.replace("\"active\":true,", ""))
        assertCorrupt(text.replace("\"brightnessRestore\":null,", ""))
    }

    @Test
    fun v2DocumentMigratesWithUnlockGraceDefaults() {
        val v2 = serializer.encode(SettingsDocument(active = false))
            .replace("\"schemaVersion\":4", "\"schemaVersion\":2")
            .replace(Regex(""","unlockGrace":\{[^}]*\}"""), "")
            .replace(",\"lastUnlockGraceEpochMillis\":null", "")
            .replace(",\"bypassPackages\":[]", "")
        val decoded = serializer.decode(v2)
        assertEquals(2, decoded.schemaVersion)
        val migrated = SettingsMigrations.migrate(decoded)
        assertEquals(4, migrated.schemaVersion)
        assertFalse(migrated.active) // the parent's choice is kept
        assertEquals(UnlockGraceSettings(), migrated.unlockGrace)
    }

    @Test
    fun keysMustMatchTheirSchemaVersion() {
        val v4 = serializer.encode(SettingsDocument())
        assertCorrupt(v4.replace("\"schemaVersion\":4", "\"schemaVersion\":2"))
        assertCorrupt(v4.replace("\"schemaVersion\":4", "\"schemaVersion\":3"))
        assertCorrupt(v4.replace(",\"lastUnlockGraceEpochMillis\":null", ""))
        assertCorrupt(v4.replace(",\"bypassPackages\":[]", ""))
    }

    @Test
    fun v3DocumentMigratesWithNoBypassApps() {
        val v3 = serializer.encode(SettingsDocument(lastUnlockGraceEpochMillis = 7L))
            .replace("\"schemaVersion\":4", "\"schemaVersion\":3")
            .replace(",\"bypassPackages\":[]", "")
        val migrated = SettingsMigrations.migrate(serializer.decode(v3))
        assertEquals(4, migrated.schemaVersion)
        assertEquals(emptyList<String>(), migrated.bypassPackages)
        assertEquals(7L, migrated.lastUnlockGraceEpochMillis)
    }

    @Test
    fun bypassPackagesAreValidated() {
        val ok = SettingsDocument(bypassPackages = listOf("com.example.game", "org.school.app"))
        assertEquals(ok, serializer.decode(serializer.encode(ok)))
        val text = serializer.encode(ok)
        assertCorrupt(text.replace("\"com.example.game\"", "\"not a package\""))
        assertCorrupt(text.replace("\"org.school.app\"", "\"com.example.game\""))
    }

    @Test
    fun unlockGraceBoundsAreEnforced() {
        val text = serializer.encode(SettingsDocument())
        assertCorrupt(text.replace("\"graceSeconds\":300", "\"graceSeconds\":30"))
        assertCorrupt(text.replace("\"restSeconds\":3600", "\"restSeconds\":60"))
        val custom = SettingsDocument(unlockGrace = UnlockGraceSettings(false, 900, 300, 14400), lastUnlockGraceEpochMillis = 123L)
        assertEquals(custom, serializer.decode(serializer.encode(custom)))
    }

    @Test
    fun v1IsNeverWritten() {
        try {
            serializer.encode(SettingsDocument(schemaVersion = 1))
            throw AssertionError("expected failure")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun effectTimingAndBrightnessBoundsAreEnforced() {
        val text = serializer.encode(SettingsDocument())
        assertCorrupt(text.replace("\"buildUpSeconds\":120", "\"buildUpSeconds\":30"))
        assertCorrupt(text.replace("\"breakSeconds\":60", "\"breakSeconds\":45"))
        assertCorrupt(text.replace("\"brightnessRestore\":null", "\"brightnessRestore\":{\"level\":300,\"automatic\":false}"))
        val withRestore = SettingsDocument(brightnessRestore = BrightnessRestore(level = 128, automatic = true))
        assertEquals(withRestore, serializer.decode(serializer.encode(withRestore)))
    }

    @Test
    fun pinCreatedMustAgreeWithCredential() {
        val text = serializer.encode(SettingsDocument()).replace("\"pinCreated\":false", "\"pinCreated\":true")
        assertCorrupt(text)
    }

    @Test
    fun oversizedInputIsCorruption() = runTest {
        val huge = ByteArray(SettingsDocumentSerializer.MAX_SERIALIZED_BYTES + 10) { ' '.code.toByte() }
        try {
            serializer.readFrom(ByteArrayInputStream(huge))
            throw AssertionError("expected CorruptionException")
        } catch (_: CorruptionException) {
            assertTrue(true)
        }
    }
}
