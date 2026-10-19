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
     * v1 → v2 (FR-031, FR-034–036): Active, default screen effects (all off, default
     * timings) and no saved brightness. The serializer already decoded v1 with those
     * defaults; this only stamps the version so the next write is v2.
     */
    val V1_TO_V2: DataMigration<SettingsDocument> = object : DataMigration<SettingsDocument> {
        override suspend fun shouldMigrate(currentData: SettingsDocument): Boolean =
            currentData.schemaVersion == SettingsDocument.LEGACY_SCHEMA_VERSION

        override suspend fun migrate(currentData: SettingsDocument): SettingsDocument = migrateV1(currentData)

        override suspend fun cleanUp() = Unit
    }

    /** Pure form of [V1_TO_V2], for tests. Idempotent: a v2 document is returned unchanged. */
    fun migrateV1(document: SettingsDocument): SettingsDocument {
        if (document.schemaVersion != SettingsDocument.LEGACY_SCHEMA_VERSION) return document
        return document.copy(
            schemaVersion = SettingsDocument.CURRENT_SCHEMA_VERSION,
            active = true,
            screenEffects = ScreenEffects(),
            brightnessRestore = null,
        )
    }

    val all: List<DataMigration<SettingsDocument>> = listOf(V1_TO_V2)
}
