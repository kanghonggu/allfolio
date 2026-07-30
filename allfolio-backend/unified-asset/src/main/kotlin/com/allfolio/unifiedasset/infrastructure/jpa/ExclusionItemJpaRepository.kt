package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.ExclusionItemEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExclusionItemJpaRepository : JpaRepository<ExclusionItemEntity, UUID> {
    fun findByListIdIn(listIds: Collection<UUID>): List<ExclusionItemEntity>
    fun existsByListIdAndSymbol(listId: UUID, symbol: String): Boolean
}
