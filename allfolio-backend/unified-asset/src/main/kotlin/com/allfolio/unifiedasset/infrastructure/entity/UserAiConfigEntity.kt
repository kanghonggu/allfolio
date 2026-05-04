package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_ai_configs")
class UserAiConfigEntity(
    @Id @Column(columnDefinition = "uuid")
    val userId: UUID,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val updatedAt: LocalDateTime,
)
