package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.common.crypto.EncryptedStringConverter
import jakarta.persistence.Convert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SensitiveEntityEncryptionTest {
    @Test
    fun `account api credentials use encrypted string converter`() {
        assertEncrypted(AccountEntity::class.java, "apiKey")
        assertEncrypted(AccountEntity::class.java, "apiSecret")
    }

    @Test
    fun `user ai api key uses encrypted string converter`() {
        assertEncrypted(UserAiConfigEntity::class.java, "apiKey")
    }

    private fun assertEncrypted(entityClass: Class<*>, fieldName: String) {
        val annotation = entityClass
            .getDeclaredField(fieldName)
            .getAnnotation(Convert::class.java)

        assertEquals(EncryptedStringConverter::class.java, annotation.converter.java)
    }
}
