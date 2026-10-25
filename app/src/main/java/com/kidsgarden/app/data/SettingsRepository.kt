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

    /**
     * Stores the Network dulling preference only. Callers revoke authorization before
     * acknowledging a stop. Turning it off is rejected when no other method is on (FR-023).
     */
    suspend fun updateVpnPreference(enabled: Boolean): Result<SettingsDocument> =
        update { requireAnyMethod(it.copy(vpnDullingEnabled = enabled)) }

    /**
     * Active/Deactivated (FR-031). Only SessionManager calls it, after a confirmed Deactivate
     * or an authenticated Activate. On failure the previous value stays in effect.
     */
    suspend fun updateActive(active: Boolean): Result<SettingsDocument> =
        update { it.copy(active = active) }

    /** The four dulling-method switches (FR-036). All four off is rejected. */
    suspend fun updateMethodSwitches(network: Boolean, grey: Boolean, volume: Boolean, brightness: Boolean): Result<SettingsDocument> {
        if (!network && !grey && !volume && !brightness) {
            return Result.failure(SettingsFailure.InvalidValue("at least one dulling method must stay on"))
        }
        return update {
            it.copy(
                vpnDullingEnabled = network,
                screenEffects = it.screenEffects.copy(
                    greyScreenEnabled = grey,
                    lowerVolumeEnabled = volume,
                    lowerBrightnessEnabled = brightness,
                ),
            )
        }
    }

    /** Shared cycle timings; values outside the allowed sets are rejected. */
    suspend fun updateEffectTiming(buildUpSeconds: Int, breakSeconds: Int): Result<SettingsDocument> =
        update { it.copy(screenEffects = it.screenEffects.copy(buildUpSeconds = buildUpSeconds, breakSeconds = breakSeconds)) }

    /** Unlock grace switch and times (FR-037); values outside the offered choices are rejected. */
    suspend fun updateUnlockGrace(enabled: Boolean, graceSeconds: Int, fadeSeconds: Int, restSeconds: Int): Result<SettingsDocument> =
        update { it.copy(unlockGrace = UnlockGraceSettings(enabled, graceSeconds, fadeSeconds, restSeconds)) }

    /** When the last unlock grace started; written by SessionManager only (FR-037). */
    suspend fun saveUnlockGraceStart(epochMillis: Long): Result<SettingsDocument> =
        update { it.copy(lastUnlockGraceEpochMillis = epochMillis) }

    /**
     * Called by ScreenEffectsController only: set once before the first brightness change in
     * a DULL period, cleared (null) after restore.
     */
    suspend fun saveBrightnessRestore(value: BrightnessRestore?): Result<SettingsDocument> =
        update { it.copy(brightnessRestore = value) }

    /** After setup, at least one dulling method must stay on (FR-023). */
    private fun requireAnyMethod(document: SettingsDocument): SettingsDocument {
        if (document.setup.onboardingComplete && !document.anyMethodEnabled) {
            throw SettingsValidationException("at least one dulling method must stay on")
        }
        return document
    }

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
