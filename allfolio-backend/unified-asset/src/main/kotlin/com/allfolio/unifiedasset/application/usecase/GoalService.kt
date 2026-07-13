package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.GoalRepository
import com.allfolio.unifiedasset.domain.goal.Goal
import com.allfolio.unifiedasset.domain.goal.GoalCategory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

data class GoalRequest(
    val name: String,
    val description: String?,
    val targetAmount: BigDecimal,
    val targetDate: LocalDate?,
    val category: GoalCategory,
)

data class GoalResponse(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val description: String?,
    val targetAmount: BigDecimal,
    val targetDate: LocalDate?,
    val category: GoalCategory,
    val currentAmount: BigDecimal,
    val progressPct: BigDecimal,
    val remainingAmount: BigDecimal,
    val daysRemaining: Long?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

data class GoalsResponse(
    val goals: List<GoalResponse>,
    val totalNav: BigDecimal,
    val generatedAt: LocalDateTime,
)

@Service
class GoalService(
    private val goalRepository: GoalRepository,
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
) {
    @Transactional
    fun create(userId: UUID, req: GoalRequest): GoalResponse {
        val goal = Goal.create(userId, req.name, req.description, req.targetAmount, req.targetDate, req.category)
        val saved = goalRepository.save(goal)
        val nav = currentNav(userId)
        return saved.toResponse(nav)
    }

    @Transactional
    fun update(userId: UUID, id: UUID, req: GoalRequest): GoalResponse {
        val existing = goalRepository.findById(id) ?: error("Goal not found")
        require(existing.userId == userId) { "Forbidden" }
        val updated = existing.copy(
            name = req.name,
            description = req.description,
            targetAmount = req.targetAmount,
            targetDate = req.targetDate,
            category = req.category,
            updatedAt = LocalDateTime.now(),
        )
        val saved = goalRepository.save(updated)
        val nav = currentNav(userId)
        return saved.toResponse(nav)
    }

    @Transactional
    fun delete(userId: UUID, id: UUID) {
        val existing = goalRepository.findById(id) ?: error("Goal not found")
        require(existing.userId == userId) { "Forbidden" }
        goalRepository.delete(id)
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID): GoalsResponse {
        val nav = currentNav(userId)
        val goals = goalRepository.findByUserId(userId)
            .sortedBy { it.createdAt }
            .map { it.toResponse(nav) }
        return GoalsResponse(goals, nav, LocalDateTime.now())
    }

    private fun currentNav(userId: UUID): BigDecimal =
        assetRepository.findByUserId(userId).navInKrw(fx)

    private fun Goal.toResponse(nav: BigDecimal): GoalResponse {
        val pct = if (targetAmount > BigDecimal.ZERO)
            nav.divide(targetAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .min(BigDecimal(100))
                .setScale(1, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        val remaining = (targetAmount - nav).coerceAtLeast(BigDecimal.ZERO)

        val daysRemaining = targetDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), it).coerceAtLeast(0) }

        return GoalResponse(
            id, userId, name, description, targetAmount, targetDate, category,
            nav, pct, remaining, daysRemaining, createdAt, updatedAt,
        )
    }
}
