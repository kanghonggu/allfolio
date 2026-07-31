package com.allfolio.unifiedasset.domain.exclusion

import java.time.LocalDateTime
import java.util.UUID

/** ADMIN 큐레이션 배제 프리셋 1건. symbol UNIQUE, 버저닝 없이 현행 1건만 보관. */
data class ExclusionPreset(
    val id: UUID,
    val symbol: String,
    val listName: String,
    val reason: String,
    val updatedBy: UUID?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
