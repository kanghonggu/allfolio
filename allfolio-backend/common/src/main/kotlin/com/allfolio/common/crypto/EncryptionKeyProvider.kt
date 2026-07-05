package com.allfolio.common.crypto

import java.util.Base64

object EncryptionKeyProvider {
    const val PROPERTY_NAME: String = "app.encryption.key"
    const val ENV_VAR: String = "APP_ENCRYPTION_KEY"
    private const val KEY_SIZE_BYTES = 32

    fun resolve(): ByteArray =
        decode(
            System.getProperty(PROPERTY_NAME)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(ENV_VAR)?.takeIf { it.isNotBlank() }
                ?: System.getenv(ENV_VAR)
        )

    fun decode(rawKey: String?): ByteArray {
        if (rawKey.isNullOrBlank()) {
            throw IllegalStateException("$PROPERTY_NAME must be set to a base64-encoded 32-byte AES key")
        }

        val decoded = try {
            Base64.getDecoder().decode(rawKey.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$PROPERTY_NAME must be valid base64", e)
        }

        if (decoded.size != KEY_SIZE_BYTES) {
            throw IllegalStateException("$PROPERTY_NAME must decode to exactly $KEY_SIZE_BYTES bytes")
        }

        return decoded
    }
}
