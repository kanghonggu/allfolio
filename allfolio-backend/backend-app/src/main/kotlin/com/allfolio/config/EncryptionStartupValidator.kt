package com.allfolio.config

import com.allfolio.common.crypto.EncryptionKeyResolver
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class EncryptionStartupValidator(
    @Value("\${ALLFOLIO_ENCRYPTION_KEY:}") private val encryptionKey: String,
) {
    @PostConstruct
    fun validate() {
        EncryptionKeyResolver.decode(encryptionKey)
    }
}
