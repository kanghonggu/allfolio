package com.allfolio.unifiedasset.domain.exclusion

import java.time.LocalDateTime
import java.util.UUID

data class ExclusionItem(
    val id: UUID,
    val listId: UUID,
    val symbol: String,
    val memo: String?,
    val addedAt: LocalDateTime,
)
