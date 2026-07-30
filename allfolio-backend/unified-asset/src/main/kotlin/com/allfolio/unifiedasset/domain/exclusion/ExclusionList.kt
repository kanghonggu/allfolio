package com.allfolio.unifiedasset.domain.exclusion

import java.time.LocalDateTime
import java.util.UUID

data class ExclusionList(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val category: String,
    val description: String?,
    val active: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val items: List<ExclusionItem> = emptyList(),
)
