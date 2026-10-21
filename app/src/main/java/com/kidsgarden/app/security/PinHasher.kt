package com.kidsgarden.app.security

import com.kidsgarden.app.data.PinCredential
import com.kidsgarden.app.data.SettingsValidationException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2 credential creation and verification (FR-005, research R8).
 *
 * algorithm: Exactly PBKDF2WithHmacSHA256; iterations: Exactly 180000 for credential version 2
 * (new credentials) and 600000 for version 1 (verified, then upgraded); derivedKeyLengthBytes: Exactly 32; salt: exactly 16 bytes from SecureRandom.
 *
 * Derivation is slow by design; call from a background dispatcher, never the main thread.
 * Parameters are fixed constants, never taken from stored JSON.
 */
class PinHasher(
    private val random: SecureRandom = SecureRandom(),
) {

    fun create(pin: CharArray): PinCredential {
        val salt = ByteArray(PinCredential.SALT_LENGTH_BYTES).also(random::nextBytes)
        val derived = derive(pin, salt, PinCredential.ITERATIONS)
        try {
            return PinCredential(
                credentialVersion = PinCredential.CURRENT_VERSION,
                algorithm = PinCredential.ALGORITHM,
                iterations = PinCredential.ITERATIONS,
                derivedKeyLengthBytes = PinCredential.KEY_LENGTH_BYTES,
                salt = Base64.getEncoder().encodeToString(salt),
                hash = Base64.getEncoder().encodeToString(derived),
            )
        } finally {
            derived.fill(0)
        }
    }

    /** False for a wrong PIN and for any malformed or unsupported credential. */
    fun verify(pin: CharArray, credential: PinCredential): Boolean {
        try {
            credential.validate()
        } catch (_: SettingsValidationException) {
            return false
        }
        val salt = Base64.getDecoder().decode(credential.salt)
        val expected = Base64.getDecoder().decode(credential.hash)
        // The round count was validated against the version above, so it is a fixed constant.
        val actual = derive(pin, salt, credential.iterations)
        try {
            // Constant-time comparison.
            return MessageDigest.isEqual(expected, actual)
        } finally {
            actual.fill(0)
            expected.fill(0)
        }
    }

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, PinCredential.KEY_LENGTH_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance(PinCredential.ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
