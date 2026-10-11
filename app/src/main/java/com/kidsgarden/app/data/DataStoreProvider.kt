package com.kidsgarden.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Storage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates the one and only settings DataStore for this process (research R7). Two
 * instances for the same file would corrupt it, so creation is guarded.
 *
 * The file lives in noBackupFilesDir and is excluded from backup and device transfer.
 */
object DataStoreProvider {

    const val FILE_NAME = "kidsgarden-settings.json"

    private val created = AtomicBoolean(false)

    /** Set when a corrupt document was replaced by defaults; read once by the parent UI. */
    @Volatile
    var repairedSinceStart: Boolean = false
        private set

    fun create(context: Context, scope: CoroutineScope): DataStore<SettingsDocument> {
        check(created.compareAndSet(false, true)) { "settings DataStore already created in this process" }
        return createForFile(File(context.noBackupFilesDir, FILE_NAME), scope)
    }

    /** Also used by instrumented tests with a temporary file. */
    internal fun createForFile(file: File, scope: CoroutineScope): DataStore<SettingsDocument> =
        createForStorage(FileStorage(SettingsDocumentSerializer) { file }, scope)

    /** The same handler and migrations over any storage; JVM tests pass their own. */
    internal fun createForStorage(storage: Storage<SettingsDocument>, scope: CoroutineScope): DataStore<SettingsDocument> =
        DataStoreFactory.create(
            storage = storage,
            corruptionHandler = ReplaceFileCorruptionHandler {
                // Whole-document reset to safe defaults (FR-026). No bytes are kept or logged.
                repairedSinceStart = true
                SettingsDocumentSerializer.defaultValue
            },
            migrations = SettingsMigrations.all,
            scope = scope,
        )
}
