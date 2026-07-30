package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.ExclusionListEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExclusionListJpaRepository : JpaRepository<ExclusionListEntity, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<ExclusionListEntity>
    fun findByUserIdAndActiveTrue(userId: UUID): List<ExclusionListEntity>
}
