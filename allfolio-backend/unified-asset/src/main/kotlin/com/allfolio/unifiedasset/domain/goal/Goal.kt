package com.allfolio.unifiedasset.domain.goal

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class GoalCategory {
    RETIREMENT, HOUSING, EDUCATION, TRAVEL, EMERGENCY, OTHER
}

data class Goal(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val description: String?,
    val targetAmount: BigDecimal,
    val targetDate: LocalDate?,
    val category: GoalCategory,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun create(
            userId: UUID,
            name: String,
            description: String?,
            targetAmount: BigDecimal,
            targetDate: LocalDate?,
            category: GoalCategory,
        ) = Goal(
            id = UUID.randomUUID(),
            userId = userId,
            name = name,
            description = description,
            targetAmount = targetAmount,
            targetDate = targetDate,
            category = category,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )
    }
}
