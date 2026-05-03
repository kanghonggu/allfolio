package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.goal.Goal
import java.util.UUID

interface GoalRepository {
    fun save(goal: Goal): Goal
    fun findById(id: UUID): Goal?
    fun findByUserId(userId: UUID): List<Goal>
    fun delete(id: UUID)
}
