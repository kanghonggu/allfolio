package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.common.crypto.EncryptedStringConverter
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_ai_configs")
class UserAiConfigEntity(
    @Id @Column(columnDefinition = "uuid")
    val userId: UUID,
    val baseUrl: String,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "api_key", nullable = false, length = 2048)
    val apiKey: String,
    val model: String,
    val updatedAt: LocalDateTime,
)
