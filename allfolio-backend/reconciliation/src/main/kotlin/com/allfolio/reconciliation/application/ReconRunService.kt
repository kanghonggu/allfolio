package com.allfolio.reconciliation.application

import com.allfolio.reconciliation.application.rules.KdMatcher
import com.allfolio.reconciliation.domain.ReconTrigger
import com.allfolio.reconciliation.domain.RunStatus
import com.allfolio.reconciliation.domain.RunType
import com.allfolio.reconciliation.domain.SummaryStatus
import com.allfolio.reconciliation.infrastructure.entity.ReconResultDetailEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconResultSummaryEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconRunEntity
import com.allfolio.reconciliation.infrastructure.jpa.ReconKdJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconResultDetailJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconResultSummaryJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconRunJpaRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 대사·검증 실행 엔진 (P2 #13, v2 스펙 §3·§8).
 *
 * - 룰=Spring 빈 List 주입, runType에 맞는 kind만 순차 실행.
 * - 룰 격리: 한 룰의 예외는 해당 summary만 FAILED(데이터 오류 vs 시스템 오류 분리),
 *   나머지 룰은 계속. 전 룰 실패 시 run FAILED.
 * - detail은 룰당 MAX_DETAILS 절단(초과분은 diff_cnt에만 반영).
 * - 같은 기준일 재실행 = 새 run 행(이력 격리).
 */
@Service
class ReconRunService(
    private val rules: List<ReconRule>,
    private val runRepository: ReconRunJpaRepository,
    private val summaryRepository: ReconResultSummaryJpaRepository,
    private val detailRepository: ReconResultDetailJpaRepository,
    private val kdRepository: ReconKdJpaRepository,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun execute(userId: UUID, runDate: LocalDate, runType: RunType, trigger: ReconTrigger): ReconRunEntity {
        val run = runRepository.save(
            ReconRunEntity(
                userId = userId, runDate = runDate, runType = runType,
                status = RunStatus.RUNNING, triggerType = trigger,
                internalAsOf = runDate,
                externalAsOf = minLastSyncedAt(userId),
            )
        )

        val targets = rules.filter { runType == RunType.ALL || it.kind.matches(runType) }
        val activeKds = kdRepository.findByUserIdAndUseYnTrue(userId)
        var failedCount = 0

        targets.forEach { rule ->
            val startedAt = System.currentTimeMillis()
            runCatching { rule.execute(ReconContext(userId, runDate)) }
                .onSuccess { result ->
                    // KD 흡수 판정 — diff 행은 남기고 kdId만 기록(숨김 아님), 집계는 분리
                    val absorbed = result.diffs.map { d -> d to KdMatcher.match(activeKds, d, runDate)?.id }
                    val summary = summaryRepository.save(
                        ReconResultSummaryEntity(
                            runId = run.id, ruleCode = rule.code,
                            status = if (result.diffs.isEmpty()) SummaryStatus.PASSED else SummaryStatus.DIFF_FOUND,
                            checkedCnt = result.checkedCnt,
                            diffCnt = result.diffs.size,
                            kdAbsorbedCnt = absorbed.count { it.second != null },
                            elapsedMs = System.currentTimeMillis() - startedAt,
                        )
                    )
                    if (absorbed.isNotEmpty()) {
                        detailRepository.saveAll(
                            absorbed.take(MAX_DETAILS).map { (d, kdId) ->
                                ReconResultDetailEntity(
                                    summaryId = summary.id, symbol = d.symbol, fieldName = d.fieldName,
                                    diffType = d.diffType, internalValue = d.internalValue,
                                    externalValue = d.externalValue, diffValue = d.diffValue,
                                    extras = d.extras.takeIf { it.isNotEmpty() }?.let { MAPPER.writeValueAsString(it) },
                                    kdId = kdId,
                                )
                            }
                        )
                    }
                }
                .onFailure { e ->
                    failedCount++
                    log.error("[Recon] rule failed code={} userId={}", rule.code, userId, e)
                    summaryRepository.save(
                        ReconResultSummaryEntity(
                            runId = run.id, ruleCode = rule.code, status = SummaryStatus.FAILED,
                            errorMsg = e.message?.take(500),
                            elapsedMs = System.currentTimeMillis() - startedAt,
                        )
                    )
                }
        }

        run.status = if (targets.isNotEmpty() && failedCount == targets.size) RunStatus.FAILED else RunStatus.COMPLETED
        run.finishedAt = LocalDateTime.now()
        return runRepository.save(run)
    }

    /** 외부 측 기준 시점 — ua_accounts는 현재 상태 테이블이라 min(lastSyncedAt)으로 근사. */
    private fun minLastSyncedAt(userId: UUID): LocalDateTime? = runCatching {
        jdbc.queryForObject(
            "SELECT MIN(last_synced_at) FROM ua_accounts WHERE user_id = ?",
            java.sql.Timestamp::class.java,
            userId,
        )?.toLocalDateTime()
    }.getOrNull()

    private fun RuleKind.matches(runType: RunType) = when (this) {
        RuleKind.VALIDATION -> runType == RunType.VALIDATION
        RuleKind.RECONCILIATION -> runType == RunType.RECONCILIATION
    }

    companion object {
        /** detail 적재 상한(룰당) — 초과분은 diff_cnt에만 반영 */
        const val MAX_DETAILS = 100
        private val MAPPER = jacksonObjectMapper()
    }
}
