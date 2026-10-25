package com.kidsgarden.app.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream

/**
 * DataStore serializer for [SettingsDocument] (research R7, data-model.md).
 *
 * - Max serialized size: 64 KiB.
 * - `schemaVersion` is read before full decoding; unsupported versions are corruption.
 *   Older versions are decoded with current defaults and then migrated by
 *   [SettingsMigrations]; a document carrying keys of a later version, or missing keys of
 *   its own version, is corruption.
 * - Unknown keys are corruption, except the legacy keys `mode` and `childSession`, which
 *   are ignored here and therefore dropped on the next write (FR-026).
 * - Any validation failure makes the whole document corrupt; DataStore's corruption
 *   handler then replaces it with safe defaults (no partial salvage).
 *
 * Errors carry a short reason only; document bytes are never included or logged.
 */
object SettingsDocumentSerializer : Serializer<SettingsDocument> {

    const val MAX_SERIALIZED_BYTES = 64 * 1024
    private val LEGACY_KEYS = setOf("mode", "childSession")

    /** Keys each schema version introduced. A document must carry exactly the keys of its version and earlier. */
    private val KEYS_ADDED_IN: Map<Int, Set<String>> = mapOf(
        2 to setOf("active", "screenEffects", "brightnessRestore"),
        3 to setOf("unlockGrace", "lastUnlockGraceEpochMillis"),
    )

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    override val defaultValue: SettingsDocument = SettingsDocument()

    override suspend fun readFrom(input: InputStream): SettingsDocument {
        val bytes = input.readNBytes(MAX_SERIALIZED_BYTES + 1)
        if (bytes.size > MAX_SERIALIZED_BYTES) throw CorruptionException("settings document too large")
        return decode(bytes.toString(Charsets.UTF_8))
    }

    override suspend fun writeTo(t: SettingsDocument, output: OutputStream) {
        output.write(encode(t).toByteArray(Charsets.UTF_8))
    }

    /** Visible for tests: decodes and validates, throwing [CorruptionException] on any problem. */
    fun decode(text: String): SettingsDocument {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val version = root["schemaVersion"]?.jsonPrimitive?.int
                ?: throw CorruptionException("missing schemaVersion")
            if (version !in SettingsDocument.SUPPORTED_SCHEMA_VERSIONS) {
                throw CorruptionException("unsupported schemaVersion")
            }
            for ((added, keys) in KEYS_ADDED_IN) {
                val present = keys.count { it in root }
                val expected = if (version >= added) keys.size else 0
                if (present != expected) throw CorruptionException("fields do not match schemaVersion")
            }
            val withoutLegacy = JsonObject(root.filterKeys { it !in LEGACY_KEYS })
            val document = json.decodeFromJsonElement(SettingsDocument.serializer(), withoutLegacy)
            document.validate()
            return document
        } catch (e: CorruptionException) {
            throw e
        } catch (e: SerializationException) {
            throw CorruptionException("settings document could not be decoded", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("settings document is malformed", e)
        } catch (e: SettingsValidationException) {
            throw CorruptionException("settings document failed validation", e)
        }
    }

    /** Visible for tests. Validates before writing so an invalid document is never stored. */
    fun encode(document: SettingsDocument): String {
        document.validate()
        check(document.schemaVersion == SettingsDocument.CURRENT_SCHEMA_VERSION) { "only the current schema is written" }
        val text = json.encodeToString(SettingsDocument.serializer(), document)
        check(text.toByteArray(Charsets.UTF_8).size <= MAX_SERIALIZED_BYTES) { "settings document too large" }
        return text
    }
}
