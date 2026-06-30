package com.allfolio.common.crypto

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class EncryptedStringConverter : AttributeConverter<String?, String?> {
    override fun convertToDatabaseColumn(attribute: String?): String? =
        attribute?.let { PREFIX + crypto.encrypt(it) }

    override fun convertToEntityAttribute(dbData: String?): String? {
        if (dbData == null) return null
        if (!dbData.startsWith(PREFIX)) return dbData
        return crypto.decrypt(dbData.removePrefix(PREFIX))
    }

    companion object {
        const val PREFIX: String = "enc:v1:"

        private val crypto: AesGcmStringCrypto by lazy {
            AesGcmStringCrypto.fromConfiguredKey()
        }

        fun validateConfiguration() {
            EncryptionKeyResolver.resolve()
        }
    }
}
