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
            // 여기만 맨 `now()`로 남는다 — 아래 [KST] 주석 참고. 저장되는 값이라 규칙이 다르다.
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

        val daysRemaining = targetDate?.let { ChronoUnit.DAYS.between(LocalDate.now(KST), it).coerceAtLeast(0) }

        return GoalResponse(
            id, userId, name, description, targetAmount, targetDate, category,
            nav, pct, remaining, daysRemaining, createdAt, updatedAt,
        )
    }

    companion object {
        /**
         * `generatedAt`은 KST 오프셋을 달아 내보내고, D-day([daysRemaining])는 KST 달력으로 센다 —
         * Render 컨테이너는 TZ 설정이 없어 UTC라 기본 타임존을 쓰면 KST 00:00~09:00에 하루가 밀린다.
         * 배경은 [ReportService.Companion], 재현은 [ReportWindowTimezoneTest] 참고.
         *
         * ## `updatedAt`(=[update])은 일부러 맨 `now()`로 남겼다
         * **저장되는 값이라 규칙이 다르다.** `ua_goals.created_at`/`updated_at`은 `timestamp`
         * (타임존 없음)이고, 같은 행의 `created_at`을 찍는 [Goal.create]도 맨 `now()`다. 여기만
         * KST로 옮기면 한 행 안에서 `updated_at`이 `created_at`보다 9시간 앞서고, 컬럼이 어느 존으로
         * 쓰였는지 기록하지 않으니 기존 UTC 행과 새 KST 행을 되돌릴 방법이 없다 — 화면에 안 보이는
         * 대신 조용히 섞인다. 게다가 이 코드베이스의 저장용 타임스탬프는 78곳 전부 맨 `now()`이고,
         * 목록 정렬([list])이 `created_at`을 읽으므로 나중에 [Goal.create]까지 옮기면 경계 전후
         * 행들이 뒤섞여 정렬된다. 제대로 고치려면 `timestamptz` 스키마 마이그레이션이 필요한데,
         * 이 저장소에는 마이그레이션 도구가 없다(손으로 관리하는 `init.sql` + `ddl-auto: none`).
         * 지금 두 필드는 화면에 렌더링되지도 않으므로 사용자에게 보이는 결함도 없다.
         */
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
