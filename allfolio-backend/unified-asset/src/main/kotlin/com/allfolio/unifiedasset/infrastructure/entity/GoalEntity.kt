package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.goal.Goal
import com.allfolio.unifiedasset.domain.goal.GoalCategory
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_goals")
class GoalEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(length = 500)
    val description: String?,

    @Column(name = "target_amount", nullable = false, precision = 30, scale = 10)
    val targetAmount: BigDecimal,

    @Column(name = "target_date")
    val targetDate: LocalDate?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val category: GoalCategory,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime,
) {
    fun toDomain() = Goal(id, userId, name, description, targetAmount, targetDate, category, createdAt, updatedAt)

    companion object {
        fun fromDomain(g: Goal) = GoalEntity(
            g.id, g.userId, g.name, g.description, g.targetAmount,
            g.targetDate, g.category, g.createdAt, g.updatedAt,
        )
    }
}
