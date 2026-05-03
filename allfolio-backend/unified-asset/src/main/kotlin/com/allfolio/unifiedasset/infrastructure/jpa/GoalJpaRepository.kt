package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.GoalEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GoalJpaRepository : JpaRepository<GoalEntity, UUID> {
    fun findByUserId(userId: UUID): List<GoalEntity>
}
