package com.allfolio.broker

import com.allfolio.common.crypto.EncryptedStringConverter
import jakarta.persistence.Convert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrokerAuthEntityEncryptionTest {
    @Test
    fun `broker auth tokens use encrypted string converter`() {
        assertEncrypted("accessToken")
        assertEncrypted("refreshToken")
    }

    private fun assertEncrypted(fieldName: String) {
        val annotation = BrokerAuthEntity::class.java
            .getDeclaredField(fieldName)
            .getAnnotation(Convert::class.java)

        assertEquals(EncryptedStringConverter::class.java, annotation.converter.java)
    }
}
