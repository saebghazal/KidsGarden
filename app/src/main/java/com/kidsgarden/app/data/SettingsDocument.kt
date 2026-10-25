package com.kidsgarden.app.data

import com.kidsgarden.app.domain.DomainSettings
import com.kidsgarden.app.domain.DullIntensity
import com.kidsgarden.app.domain.EffectCycle
import com.kidsgarden.app.domain.ScreenEffect
import com.kidsgarden.app.domain.UnlockGrace
import kotlinx.serialization.Serializable

/**
 * Persisted SettingsDocument v3 (data-model.md). Parent settings only: there is no
 * authorization mode, child session, pause, token, or call field (FR-025).
 *
 * | Field | Type / default | Validation and meaning |
 * | schemaVersion | Int / 3 | Explicit positive supported version (1, 2 or 3); older versions are migrated |
 * | active | Boolean / true | Parent Active/Deactivated choice (FR-031); false only after a confirmed Deactivate |
 * | pinCredential | PinCredential? / null | Null means enrollment/recovery, never empty-PIN access |
 * | dullIntensity | Enum / MEDIUM | GENTLE, MEDIUM, STRONG |
 * | vpnDullingEnabled | Boolean / false | Network dulling switch; parent preference, not live consent or running-service proof |
 * | screenEffects | ScreenEffects | Grey screen, lower media volume, lower brightness switches and cycle timings |
 * | brightnessRestore | BrightnessRestore? / null | Parent brightness saved before the brightness effect first lowers it |
 * | unlockGrace | UnlockGraceSettings | Unlock grace switch (on by default) and grace, fade and rest times (FR-037) |
 * | lastUnlockGraceEpochMillis | Long? / null | When the last unlock grace started; only so a restart cannot reset the rest |
 * | vpnDullingConfiguration | ProfileConfiguration | Canonical profile map, no redundant top-level cap values |
 * | softLandingDurationSeconds | Int / 120 | Exactly 60 or 120 |
 * | setup | SetupFlags | pinCreated and onboardingComplete; permission status is runtime-derived |
 */
@Serializable
data class SettingsDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val active: Boolean = true,
    val pinCredential: PinCredential? = null,
    val dullIntensity: DullIntensity = DullIntensity.MEDIUM,
    val vpnDullingEnabled: Boolean = false,
    val screenEffects: ScreenEffects = ScreenEffects(),
    val brightnessRestore: BrightnessRestore? = null,
    val unlockGrace: UnlockGraceSettings = UnlockGraceSettings(),
    val lastUnlockGraceEpochMillis: Long? = null,
    val vpnDullingConfiguration: Map<DullIntensity, ThrottleSettings> = DEFAULT_PROFILES,
    val softLandingDurationSeconds: Int = 120,
    val setup: SetupFlags = SetupFlags(),
) {
    /**
     * Throws [SettingsValidationException] if any field violates data-model.md.
     * Any violation makes the whole document unusable (FR-026: no partial salvage).
     */
    fun validate() {
        check(schemaVersion in SUPPORTED_SCHEMA_VERSIONS) { "unsupported schemaVersion $schemaVersion" }
        screenEffects.validate()
        brightnessRestore?.validate()
        unlockGrace.validate()
        lastUnlockGraceEpochMillis?.let { check(it >= 0L) { "lastUnlockGraceEpochMillis must not be negative" } }
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
        active = active,
        networkEnabled = vpnDullingEnabled,
        enabledEffects = screenEffects.enabled(),
        unlockGraceEnabled = unlockGrace.enabled,
        unlockGraceSeconds = unlockGrace.graceSeconds,
        unlockGraceFadeSeconds = unlockGrace.fadeSeconds,
        unlockGraceRestSeconds = unlockGrace.restSeconds,
        lastUnlockGraceEpochMillis = lastUnlockGraceEpochMillis,
    )

    /** True when at least one of the four dulling methods is switched on (FR-023). */
    val anyMethodEnabled: Boolean
        get() = vpnDullingEnabled || screenEffects.enabled().isNotEmpty()

    private inline fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw SettingsValidationException(message())
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
        const val LEGACY_SCHEMA_VERSION = 1
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2, CURRENT_SCHEMA_VERSION)

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
 * | credentialVersion | 2 (1 still accepted and upgraded on the next correct PIN) |
 * | algorithm | Exactly PBKDF2WithHmacSHA256 |
 * | iterations | Exactly 180000 for credential version 2; exactly 600000 for version 1 |
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
        val expectedIterations = ITERATIONS_BY_VERSION[credentialVersion]
            ?: throw SettingsValidationException("unsupported credentialVersion")
        if (algorithm != ALGORITHM) throw SettingsValidationException("unsupported algorithm")
        // Fixed per version; a work factor is never taken from the stored JSON.
        if (iterations != expectedIterations) throw SettingsValidationException("unsupported iterations")
        if (derivedKeyLengthBytes != KEY_LENGTH_BYTES) throw SettingsValidationException("unsupported key length")
        if (decodedLength(salt) != SALT_LENGTH_BYTES) throw SettingsValidationException("salt must decode to 16 bytes")
        if (decodedLength(hash) != KEY_LENGTH_BYTES) throw SettingsValidationException("hash must decode to 32 bytes")
    }

    private fun decodedLength(value: String): Int = try {
        java.util.Base64.getDecoder().decode(value).size
    } catch (_: IllegalArgumentException) {
        -1
    }

    /** True when this credential should be re-hashed with the current version. */
    val outdated: Boolean get() = credentialVersion != CURRENT_VERSION

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"

        /** Owner change 2026-10-21: 70% fewer rounds than v1 for a faster unlock. */
        const val CURRENT_VERSION = 2
        const val ITERATIONS = 180_000
        const val LEGACY_ITERATIONS = 600_000
        val ITERATIONS_BY_VERSION: Map<Int, Int> = mapOf(1 to LEGACY_ITERATIONS, CURRENT_VERSION to ITERATIONS)
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

/**
 * Screen and sound effect switches and the shared cycle timings (FR-034–036). Maximum
 * strengths are fixed in code, not stored.
 */
@Serializable
data class ScreenEffects(
    val greyScreenEnabled: Boolean = false,
    val lowerVolumeEnabled: Boolean = false,
    val lowerBrightnessEnabled: Boolean = false,
    val buildUpSeconds: Int = EffectCycle.DEFAULT_BUILD_UP_SECONDS,
    val breakSeconds: Int = EffectCycle.DEFAULT_BREAK_SECONDS,
) {
    fun validate() {
        if (buildUpSeconds !in EffectCycle.BUILD_UP_SECONDS) throw SettingsValidationException("buildUpSeconds not allowed")
        if (breakSeconds !in EffectCycle.BREAK_SECONDS) throw SettingsValidationException("breakSeconds not allowed")
    }

    fun enabled(): Set<ScreenEffect> = buildSet {
        if (greyScreenEnabled) add(ScreenEffect.GREY_SCREEN)
        if (lowerVolumeEnabled) add(ScreenEffect.LOWER_VOLUME)
        if (lowerBrightnessEnabled) add(ScreenEffect.LOWER_BRIGHTNESS)
    }
}

/**
 * The parent's brightness before the brightness effect first lowered it. Written once per
 * DULL period, cleared after restore. The parent's own device setting, not child activity.
 */
@Serializable
data class BrightnessRestore(
    /** 0–255, as read from `Settings.System.SCREEN_BRIGHTNESS`. */
    val level: Int,
    /** The previous `SCREEN_BRIGHTNESS_MODE` was automatic. */
    val automatic: Boolean,
) {
    fun validate() {
        if (level !in 0..255) throw SettingsValidationException("brightness level out of range")
    }
}

/**
 * Unlock grace (FR-037, owner change 2026-10-25): on by default. Times are limited to the
 * offered choices.
 */
@Serializable
data class UnlockGraceSettings(
    val enabled: Boolean = UnlockGrace.DEFAULT_ENABLED,
    val graceSeconds: Int = UnlockGrace.DEFAULT_GRACE_SECONDS,
    val fadeSeconds: Int = UnlockGrace.DEFAULT_FADE_SECONDS,
    val restSeconds: Int = UnlockGrace.DEFAULT_REST_SECONDS,
) {
    fun validate() {
        if (graceSeconds !in UnlockGrace.GRACE_SECONDS) throw SettingsValidationException("graceSeconds not allowed")
        if (fadeSeconds !in UnlockGrace.FADE_SECONDS) throw SettingsValidationException("fadeSeconds not allowed")
        if (restSeconds !in UnlockGrace.REST_SECONDS) throw SettingsValidationException("restSeconds not allowed")
    }
}
