package com.kidsgarden.app.data

import androidx.datastore.core.DataMigration

/**
 * Registry of settings schema migrations (Constitution: Settings schema).
 *
 * Every schemaVersion bump adds one migration from each previously released version, each
 * with a unit test. Migrations are deterministic and idempotent, never access DataStore
 * themselves, and never restore authorization (authorization is not persisted at all).
 */
object SettingsMigrations {

    /**
     * Every older version to v4. The serializer already decoded the older document with current
     * defaults for every newer field; this resets those fields explicitly and stamps the
     * version so the next write is current.
     *
     * - v1 → v2 (FR-031, FR-034–036): Active, default screen effects, no saved brightness.
     * - v2 → v3 (FR-037): unlock grace with its defaults (on, 5 min, 2 min fade, 60 min
     *   rest) and no previous grace.
     * - v3 → v4 (FR-038): an empty list of apps that skip Kids Garden.
     */
    val TO_CURRENT: DataMigration<SettingsDocument> = object : DataMigration<SettingsDocument> {
        override suspend fun shouldMigrate(currentData: SettingsDocument): Boolean =
            currentData.schemaVersion < SettingsDocument.CURRENT_SCHEMA_VERSION

        override suspend fun migrate(currentData: SettingsDocument): SettingsDocument = SettingsMigrations.migrate(currentData)

        override suspend fun cleanUp() = Unit
    }

    /** Pure form of [TO_CURRENT], for tests. Idempotent: a current document is returned unchanged. */
    fun migrate(document: SettingsDocument): SettingsDocument {
        var doc = document
        if (doc.schemaVersion == 1) {
            doc = doc.copy(schemaVersion = 2, active = true, screenEffects = ScreenEffects(), brightnessRestore = null)
        }
        if (doc.schemaVersion == 2) {
            doc = doc.copy(schemaVersion = 3, unlockGrace = UnlockGraceSettings(), lastUnlockGraceEpochMillis = null)
        }
        if (doc.schemaVersion == 3) {
            doc = doc.copy(schemaVersion = 4, bypassPackages = emptyList())
        }
        return doc
    }

    val all: List<DataMigration<SettingsDocument>> = listOf(TO_CURRENT)
}
