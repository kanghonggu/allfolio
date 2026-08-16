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
import java.time.OffsetDateTime
import java.time.ZoneId
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
    val generatedAt: OffsetDateTime,
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
        val existing = goalRepository.findById(id)
            ?: throw NoSuchElementException("Goal not found: $id")
        if (existing.userId != userId) throw NoSuchElementException("Goal not found: $id")
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
        val existing = goalRepository.findById(id)
            ?: throw NoSuchElementException("Goal not found: $id")
        if (existing.userId != userId) throw NoSuchElementException("Goal not found: $id")
        goalRepository.delete(id)
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID): GoalsResponse {
        val nav = currentNav(userId)
        val goals = goalRepository.findByUserId(userId)
            .sortedBy { it.createdAt }
            .map { it.toResponse(nav) }
        return GoalsResponse(goals, nav, OffsetDateTime.now(KST))
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

    companion object {
        /**
         * `generatedAt`은 KST 오프셋을 달아 내보낸다 — Render 컨테이너는 TZ 설정이 없어 UTC라
         * 기본 타임존을 쓰면 한국 사용자에게 9시간 어긋난다. 배경은 [ReportService.Companion] 참고.
         */
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
