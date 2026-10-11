package com.kidsgarden.app.data

import androidx.datastore.core.DataMigration

/**
 * Registry of settings schema migrations (Constitution: Settings schema).
 *
 * V1 has shipped no earlier schema, so the list is empty. Every future schemaVersion bump
 * must add one migration from each previously released version, each with a unit test.
 * Migrations must be deterministic and idempotent, must not access DataStore themselves,
 * and must never restore authorization (authorization is not persisted at all).
 */
object SettingsMigrations {
    val all: List<DataMigration<SettingsDocument>> = emptyList()
}
