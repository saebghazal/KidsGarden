package com.kidsgarden.app.data

import androidx.datastore.core.DataStore
import com.kidsgarden.app.domain.DomainSettings
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Typed failures (contracts/settings-repository.md). Messages never contain document bytes. */
sealed class SettingsFailure(message: String) : Exception(message) {
    class InvalidValue(message: String) : SettingsFailure(message)
    class StaleVersion : SettingsFailure("credential changed since it was read")
    class IoUnavailable : SettingsFailure("settings storage unavailable")
}

/**
 * The sole writer of persisted settings. Stores parent settings only; authorization,
 * sessions, pauses, and tokens are never written (FR-025). Writes happen only for
 * parent settings changes.
 */
class SettingsRepository(
    private val dataStore: DataStore<SettingsDocument>,
) : SettingsStore {

    /** Validated snapshots. Corrupt content was already replaced by safe defaults. */
    fun observeSettings(): Flow<SettingsDocument> = dataStore.data

    override val settings: Flow<DomainSettings> = dataStore.data.map { it.toDomainSettings() }

    suspend fun load(): Result<SettingsDocument> = try {
        Result.success(dataStore.data.first())
    } catch (_: IOException) {
        Result.failure(SettingsFailure.IoUnavailable())
    }

    suspend fun createParentCredential(credential: PinCredential): Result<SettingsDocument> =
        update { current ->
            credential.validate()
            current.copy(pinCredential = credential, setup = current.setup.copy(pinCreated = true))
        }

    suspend fun updateIntensity(intensity: DullIntensity): Result<SettingsDocument> =
        update { it.copy(dullIntensity = intensity) }

    suspend fun updateSoftLandingDuration(seconds: Int): Result<SettingsDocument> {
        if (seconds != 60 && seconds != 120) {
            return Result.failure(SettingsFailure.InvalidValue("softLandingDurationSeconds must be 60 or 120"))
        }
        return update { it.copy(softLandingDurationSeconds = seconds) }
    }

    /** Stores the preference only. Callers revoke authorization before acknowledging a stop. */
    suspend fun updateVpnPreference(enabled: Boolean): Result<SettingsDocument> =
        update { it.copy(vpnDullingEnabled = enabled) }

    suspend fun updateSetupFlags(flags: SetupFlags): Result<SettingsDocument> =
        update { current ->
            if (flags.pinCreated != (current.pinCredential != null)) {
                throw SettingsValidationException("pinCreated must agree with credential")
            }
            current.copy(setup = flags)
        }

    /**
     * Replaces the credential only if the stored one still equals [expected]. On any
     * failure the old credential stays valid.
     */
    suspend fun changeCredential(expected: PinCredential, newCredential: PinCredential): Result<SettingsDocument> {
        var stale = false
        val result = update { current ->
            if (current.pinCredential != expected) {
                stale = true
                current
            } else {
                newCredential.validate()
                current.copy(pinCredential = newCredential)
            }
        }
        return if (stale) Result.failure(SettingsFailure.StaleVersion()) else result
    }

    private suspend fun update(transform: (SettingsDocument) -> SettingsDocument): Result<SettingsDocument> =
        try {
            Result.success(
                dataStore.updateData { current ->
                    transform(current).also { it.validate() }
                },
            )
        } catch (e: SettingsValidationException) {
            Result.failure(SettingsFailure.InvalidValue(e.message ?: "invalid value"))
        } catch (_: IOException) {
            Result.failure(SettingsFailure.IoUnavailable())
        }
}
