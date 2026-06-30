package com.allfolio.common.crypto

import java.util.Base64

object EncryptionKeyResolver {
    const val ENV_VAR: String = "ALLFOLIO_ENCRYPTION_KEY"
    private const val KEY_SIZE_BYTES = 32

    fun resolve(): ByteArray =
        decode(System.getProperty(ENV_VAR)?.takeIf { it.isNotBlank() } ?: System.getenv(ENV_VAR))

    fun decode(rawKey: String?): ByteArray {
        if (rawKey.isNullOrBlank()) {
            throw IllegalStateException("$ENV_VAR must be set to a base64-encoded 32-byte AES key")
        }

        val decoded = try {
            Base64.getDecoder().decode(rawKey.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$ENV_VAR must be valid base64", e)
        }

        if (decoded.size != KEY_SIZE_BYTES) {
            throw IllegalStateException("$ENV_VAR must decode to exactly $KEY_SIZE_BYTES bytes")
        }

        return decoded
    }
}
