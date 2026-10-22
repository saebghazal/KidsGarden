package com.kidsgarden.app.security

import com.kidsgarden.app.data.PinCredential
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Builds a credential exactly as version 1 stored it (600000 rounds), for upgrade tests. */
internal object LegacyCredentials {
    fun v1(pin: String, salt: ByteArray = ByteArray(16) { it.toByte() }): PinCredential {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 600_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return PinCredential(
            credentialVersion = 1,
            algorithm = "PBKDF2WithHmacSHA256",
            iterations = 600_000,
            derivedKeyLengthBytes = 32,
            salt = Base64.getEncoder().encodeToString(salt),
            hash = Base64.getEncoder().encodeToString(hash),
        )
    }
}
