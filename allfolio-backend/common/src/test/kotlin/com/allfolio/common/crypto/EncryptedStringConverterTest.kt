package com.allfolio.common.crypto

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EncryptedStringConverterTest {
    private val key = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="

    @BeforeEach
    fun setUp() {
        System.setProperty(EncryptionKeyProvider.PROPERTY_NAME, key)
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty(EncryptionKeyProvider.PROPERTY_NAME)
    }

    @Test
    fun `encrypts database values and decrypts them back`() {
        val converter = EncryptedStringConverter()

        val encrypted = converter.convertToDatabaseColumn("secret-token")

        assertTrue(encrypted!!.startsWith(EncryptedStringConverter.PREFIX))
        assertNotEquals("secret-token", encrypted)
        assertEquals("secret-token", converter.convertToEntityAttribute(encrypted))
    }

    @Test
    fun `uses a fresh iv so the same plaintext produces different ciphertext`() {
        val converter = EncryptedStringConverter()

        val first = converter.convertToDatabaseColumn("same-secret")
        val second = converter.convertToDatabaseColumn("same-secret")

        assertNotEquals(first, second)
    }

    @Test
    fun `keeps null values as null`() {
        val converter = EncryptedStringConverter()

        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }

    @Test
    fun `rejects legacy plaintext database values`() {
        val converter = EncryptedStringConverter()

        assertThrows(LegacyPlaintextDetectedException::class.java) {
            converter.convertToEntityAttribute("legacy-secret")
        }
    }

    @Test
    fun `wraps encrypted payload decryption failures as reconnection required`() {
        val converter = EncryptedStringConverter()

        assertThrows(SensitiveDataReconnectionRequiredException::class.java) {
            converter.convertToEntityAttribute("${EncryptedStringConverter.PREFIX}not-valid-ciphertext")
        }
    }

    @Test
    fun `requires a configured 32 byte base64 key`() {
        assertThrows(IllegalStateException::class.java) {
            EncryptionKeyProvider.decode(null)
        }
        assertThrows(IllegalStateException::class.java) {
            EncryptionKeyProvider.decode("")
        }
        assertThrows(IllegalStateException::class.java) {
            EncryptionKeyProvider.decode("not-base64")
        }
    }
}
