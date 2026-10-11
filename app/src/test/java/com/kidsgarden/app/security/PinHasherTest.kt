package com.kidsgarden.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PinHasherTest {

    private val hasher = PinHasher()

    @Test
    fun correctPinVerifies() {
        val credential = hasher.create("123456".toCharArray())
        assertTrue(hasher.verify("123456".toCharArray(), credential))
    }

    @Test
    fun wrongPinIsRejected() {
        val credential = hasher.create("123456".toCharArray())
        assertFalse(hasher.verify("123457".toCharArray(), credential))
    }

    @Test
    fun credentialHasExactParameters() {
        val c = hasher.create("123456".toCharArray())
        assertEquals(1, c.credentialVersion)
        assertEquals("PBKDF2WithHmacSHA256", c.algorithm)
        assertEquals(600_000, c.iterations)
        assertEquals(32, c.derivedKeyLengthBytes)
        assertEquals(16, Base64.getDecoder().decode(c.salt).size)
        assertEquals(32, Base64.getDecoder().decode(c.hash).size)
    }

    @Test
    fun identicalPinsGetDistinctSalts() {
        val a = hasher.create("123456".toCharArray())
        val b = hasher.create("123456".toCharArray())
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.hash, b.hash)
    }

    @Test
    fun rawPinNeverAppearsInCredential() {
        val c = hasher.create("123456".toCharArray())
        assertFalse(c.toString().contains("123456"))
    }

    @Test
    fun malformedOrUnsupportedCredentialsNeverVerify() {
        val good = hasher.create("123456".toCharArray())
        val pin = "123456"
        assertFalse(hasher.verify(pin.toCharArray(), good.copy(iterations = 1)))
        assertFalse(hasher.verify(pin.toCharArray(), good.copy(algorithm = "PBKDF2WithHmacSHA1")))
        assertFalse(hasher.verify(pin.toCharArray(), good.copy(salt = "not-base64!")))
        assertFalse(hasher.verify(pin.toCharArray(), good.copy(hash = Base64.getEncoder().encodeToString(ByteArray(8)))))
    }
}
