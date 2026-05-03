package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.GoalRepository
import com.allfolio.unifiedasset.domain.goal.Goal
import com.allfolio.unifiedasset.infrastructure.entity.GoalEntity
import com.allfolio.unifiedasset.infrastructure.jpa.GoalJpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class GoalRepositoryImpl(private val jpa: GoalJpaRepository) : GoalRepository {
    override fun save(goal: Goal): Goal = jpa.save(GoalEntity.fromDomain(goal)).toDomain()
    override fun findById(id: UUID): Goal? = jpa.findById(id).orElse(null)?.toDomain()
    override fun findByUserId(userId: UUID): List<Goal> = jpa.findByUserId(userId).map { it.toDomain() }
    override fun delete(id: UUID) = jpa.deleteById(id)
}
