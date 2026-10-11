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
        assertEquals(1, d.schemaVersion)
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
        val future = serializer.encode(SettingsDocument()).replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertCorrupt(future)
    }

    @Test
    fun outOfRangeValuesAreCorruption() {
        val text = serializer.encode(SettingsDocument())
        assertCorrupt(text.replace("\"softLandingDurationSeconds\":120", "\"softLandingDurationSeconds\":90"))
        assertCorrupt(text.replace("\"bandwidthKbps\":64", "\"bandwidthKbps\":0"))
        assertCorrupt(text.replace("\"dullIntensity\":\"MEDIUM\"", "\"dullIntensity\":\"EXTREME\""))
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
