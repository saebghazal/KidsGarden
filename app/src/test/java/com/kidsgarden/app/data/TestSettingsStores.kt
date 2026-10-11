package com.kidsgarden.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Settings DataStore for JVM tests: the production serializer, corruption handler and
 * migrations over Okio storage. DataStore's FileStorage replaces the file with
 * File.renameTo, which fails on Windows when the file exists, so a second write would
 * fail there; Okio replaces it atomically. Devices keep using FileStorage.
 */
internal object TestSettingsStores {

    private object Adapter : OkioSerializer<SettingsDocument> {
        override val defaultValue: SettingsDocument = SettingsDocumentSerializer.defaultValue

        override suspend fun readFrom(source: BufferedSource): SettingsDocument =
            SettingsDocumentSerializer.readFrom(source.inputStream())

        override suspend fun writeTo(t: SettingsDocument, sink: BufferedSink) =
            SettingsDocumentSerializer.writeTo(t, sink.outputStream())
    }

    fun forFile(file: File, scope: CoroutineScope): DataStore<SettingsDocument> =
        DataStoreProvider.createForStorage(
            OkioStorage(FileSystem.SYSTEM, Adapter) { file.absoluteFile.toOkioPath() },
            scope,
        )
}
