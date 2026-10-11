package com.kidsgarden.app.data

import com.kidsgarden.app.domain.DomainSettings
import com.kidsgarden.app.domain.DullIntensity
import kotlinx.serialization.Serializable

/**
 * Persisted SettingsDocument v1 (data-model.md). Parent settings only: there is no
 * authorization mode, child session, pause, token, or call field (FR-025).
 *
 * | Field | Type / default | Validation and meaning |
 * | schemaVersion | Int / 1 | Explicit positive supported version |
 * | pinCredential | PinCredential? / null | Null means enrollment/recovery, never empty-PIN access |
 * | dullIntensity | Enum / MEDIUM | GENTLE, MEDIUM, STRONG |
 * | vpnDullingEnabled | Boolean / false | Parent preference, not live consent or running-service proof |
 * | vpnDullingConfiguration | ProfileConfiguration | Canonical profile map, no redundant top-level cap values |
 * | softLandingDurationSeconds | Int / 120 | Exactly 60 or 120 |
 * | setup | SetupFlags | pinCreated and onboardingComplete; permission status is runtime-derived |
 */
@Serializable
data class SettingsDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val pinCredential: PinCredential? = null,
    val dullIntensity: DullIntensity = DullIntensity.MEDIUM,
    val vpnDullingEnabled: Boolean = false,
    val vpnDullingConfiguration: Map<DullIntensity, ThrottleSettings> = DEFAULT_PROFILES,
    val softLandingDurationSeconds: Int = 120,
    val setup: SetupFlags = SetupFlags(),
) {
    /**
     * Throws [SettingsValidationException] if any field violates data-model.md.
     * Any violation makes the whole document unusable (FR-026: no partial salvage).
     */
    fun validate() {
        check(schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported schemaVersion $schemaVersion" }
        check(softLandingDurationSeconds == 60 || softLandingDurationSeconds == 120) {
            "softLandingDurationSeconds must be exactly 60 or 120"
        }
        check(vpnDullingConfiguration.keys == DullIntensity.entries.toSet()) {
            "profile map must contain exactly GENTLE, MEDIUM, STRONG"
        }
        vpnDullingConfiguration.values.forEach { it.validate() }
        val gentle = vpnDullingConfiguration.getValue(DullIntensity.GENTLE)
        val medium = vpnDullingConfiguration.getValue(DullIntensity.MEDIUM)
        val strong = vpnDullingConfiguration.getValue(DullIntensity.STRONG)
        check(gentle.bandwidthKbps >= medium.bandwidthKbps && medium.bandwidthKbps >= strong.bandwidthKbps) {
            "bandwidth must be monotonic across intensities"
        }
        check(gentle.addedRttLatencyMs <= medium.addedRttLatencyMs && medium.addedRttLatencyMs <= strong.addedRttLatencyMs) {
            "latency must be monotonic across intensities"
        }
        pinCredential?.validate()
        check(setup.pinCreated == (pinCredential != null)) { "pinCreated must agree with credential" }
    }

    fun toDomainSettings(): DomainSettings = DomainSettings(
        dullIntensity = dullIntensity,
        softLandingDurationSeconds = softLandingDurationSeconds,
        credentialPresent = pinCredential != null,
        onboardingComplete = setup.onboardingComplete,
    )

    private inline fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw SettingsValidationException(message())
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /** Initial values: Gentle 256/300; Medium 128/600; Strong 64/1000. */
        val DEFAULT_PROFILES: Map<DullIntensity, ThrottleSettings> = mapOf(
            DullIntensity.GENTLE to ThrottleSettings(bandwidthKbps = 256, addedRttLatencyMs = 300),
            DullIntensity.MEDIUM to ThrottleSettings(bandwidthKbps = 128, addedRttLatencyMs = 600),
            DullIntensity.STRONG to ThrottleSettings(bandwidthKbps = 64, addedRttLatencyMs = 1000),
        )
    }
}

/** Aggregate per-direction cap and added round-trip delay for one dull profile. */
@Serializable
data class ThrottleSettings(
    val bandwidthKbps: Int,
    val addedRttLatencyMs: Int,
) {
    /** Defensive v1 bounds: 1–100000 kbps and 0–5000 ms. */
    fun validate() {
        if (bandwidthKbps !in 1..100_000) throw SettingsValidationException("bandwidthKbps out of range")
        if (addedRttLatencyMs !in 0..5_000) throw SettingsValidationException("addedRttLatencyMs out of range")
    }
}

/**
 * Salted, hardened PIN verifier (FR-005, research R8). Never a raw or reversible PIN.
 *
 * | credentialVersion | 1 |
 * | algorithm | Exactly PBKDF2WithHmacSHA256 |
 * | iterations | Exactly 600000 for credential version 1 |
 * | derivedKeyLengthBytes | Exactly 32 |
 * | salt | Base64 decoding to exactly 16 bytes from SecureRandom |
 * | hash | Base64 decoding to exactly 32 verifier bytes |
 */
@Serializable
data class PinCredential(
    val credentialVersion: Int,
    val algorithm: String,
    val iterations: Int,
    val derivedKeyLengthBytes: Int,
    val salt: String,
    val hash: String,
) {
    fun validate() {
        if (credentialVersion != 1) throw SettingsValidationException("unsupported credentialVersion")
        if (algorithm != ALGORITHM) throw SettingsValidationException("unsupported algorithm")
        if (iterations != ITERATIONS) throw SettingsValidationException("unsupported iterations")
        if (derivedKeyLengthBytes != KEY_LENGTH_BYTES) throw SettingsValidationException("unsupported key length")
        if (decodedLength(salt) != SALT_LENGTH_BYTES) throw SettingsValidationException("salt must decode to 16 bytes")
        if (decodedLength(hash) != KEY_LENGTH_BYTES) throw SettingsValidationException("hash must decode to 32 bytes")
    }

    private fun decodedLength(value: String): Int = try {
        java.util.Base64.getDecoder().decode(value).size
    } catch (_: IllegalArgumentException) {
        -1
    }

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val ITERATIONS = 600_000
        const val KEY_LENGTH_BYTES = 32
        const val SALT_LENGTH_BYTES = 16
    }
}

/** Setup progress. Permission status is never stored; it is checked live. */
@Serializable
data class SetupFlags(
    val pinCreated: Boolean = false,
    val onboardingComplete: Boolean = false,
)

class SettingsValidationException(message: String) : Exception(message)
