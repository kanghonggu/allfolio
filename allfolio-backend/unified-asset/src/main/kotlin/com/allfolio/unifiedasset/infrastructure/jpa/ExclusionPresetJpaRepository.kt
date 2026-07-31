package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.ExclusionPresetEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExclusionPresetJpaRepository : JpaRepository<ExclusionPresetEntity, UUID> {
    fun findBySymbol(symbol: String): ExclusionPresetEntity?
}
