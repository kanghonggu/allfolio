package com.allfolio.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EncryptionStartupValidatorTest {
    private val key = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="

    @Test
    fun `accepts configured encryption key`() {
        assertDoesNotThrow {
            EncryptionStartupValidator(key).validate()
        }
    }

    @Test
    fun `fails startup when encryption key is missing`() {
        assertThrows(IllegalStateException::class.java) {
            EncryptionStartupValidator("").validate()
        }
    }
}
