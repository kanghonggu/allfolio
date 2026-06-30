package com.allfolio.common.crypto

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesGcmStringCrypto(
    keyBytes: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val key = SecretKeySpec(keyBytes.copyOf(), "AES")

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_SIZE_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(encodedPayload: String): String {
        val payload = try {
            Base64.getDecoder().decode(encodedPayload)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Encrypted value is not valid base64", e)
        }

        if (payload.size <= IV_SIZE_BYTES) {
            throw IllegalStateException("Encrypted value is too short")
        }

        val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = payload.copyOfRange(IV_SIZE_BYTES, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128

        fun fromConfiguredKey(): AesGcmStringCrypto =
            AesGcmStringCrypto(EncryptionKeyResolver.resolve())
    }
}
