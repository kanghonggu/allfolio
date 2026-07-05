package com.allfolio.config

import com.allfolio.common.crypto.EncryptionKeyProvider
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class EncryptionStartupValidator(
    @Value("\${app.encryption.key:}") private val encryptionKey: String,
) {
    @PostConstruct
    fun validate() {
        EncryptionKeyProvider.decode(encryptionKey)
    }
}
