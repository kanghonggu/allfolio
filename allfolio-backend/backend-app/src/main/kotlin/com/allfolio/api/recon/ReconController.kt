package com.allfolio.api.recon

import com.allfolio.reconciliation.application.ReconRunService
import com.allfolio.reconciliation.domain.ReconTrigger
import com.allfolio.reconciliation.domain.RunType
import com.allfolio.reconciliation.infrastructure.entity.ReconResultDetailEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconResultSummaryEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconRunEntity
import com.allfolio.reconciliation.infrastructure.jpa.ReconResultDetailJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconResultSummaryJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconRunJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * 대사·검증 실행/조회 API (P2 #13, v2 스펙 §7) — USER 본인 스코프.
 * 타인 run 접근은 404로 은닉(AccountController 패턴).
 */
@RestController
@RequestMapping("/api/recon")
class ReconController(
    private val reconRunService: ReconRunService,
    private val runRepository: ReconRunJpaRepository,
    private val summaryRepository: ReconResultSummaryJpaRepository,
    private val detailRepository: ReconResultDetailJpaRepository,
) {
    @PostMapping("/runs")
    fun execute(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody req: ExecuteRunRequest,
    ): ReconRunResponse =
        reconRunService.execute(userId, req.runDate, req.runType, ReconTrigger.MANUAL).toResponse()

    @GetMapping("/runs")
    fun list(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): List<ReconRunResponse> {
        val page = PageRequest.of(0, 50)
        val runs = if (from != null && to != null) {
            runRepository.findByUserIdAndRunDateBetweenOrderByStartedAtDesc(userId, from, to, page)
        } else {
            runRepository.findByUserIdOrderByStartedAtDesc(userId, page)
        }
        return runs.map { it.toResponse() }
    }

    @GetMapping("/runs/{id}")
    fun get(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): ReconRunDetailResponse {
        val run = ownedRun(userId, id)
        return ReconRunDetailResponse(
            run = run.toResponse(),
            summaries = summaryRepository.findByRunId(run.id).map { it.toResponse() },
        )
    }

    @GetMapping("/runs/{id}/details")
    fun details(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
        @RequestParam(required = false) ruleCode: String?,
        @RequestParam(required = false) symbol: String?,
    ): List<ReconDetailResponse> {
        val run = ownedRun(userId, id)
        val summaries = summaryRepository.findByRunId(run.id)
            .filter { ruleCode == null || it.ruleCode == ruleCode }
        if (summaries.isEmpty()) return emptyList()
        val byId = summaries.associateBy { it.id }
        return detailRepository.findBySummaryIdIn(byId.keys)
            .filter { symbol == null || it.symbol.equals(symbol, ignoreCase = true) }
            .map { it.toResponse(byId.getValue(it.summaryId).ruleCode) }
    }

    private fun ownedRun(userId: UUID, id: UUID): ReconRunEntity {
        val run = runRepository.findById(id).orElse(null)
        if (run == null || run.userId != userId) throw NoSuchElementException("Run not found: $id")
        return run
    }

    private fun ReconRunEntity.toResponse() = ReconRunResponse(
        id = id, runDate = runDate.toString(), runType = runType.name, status = status.name,
        triggerType = triggerType.name, internalAsOf = internalAsOf?.toString(),
        externalAsOf = externalAsOf?.toString(), startedAt = startedAt.toString(),
        finishedAt = finishedAt?.toString(),
    )

    private fun ReconResultSummaryEntity.toResponse() = ReconSummaryResponse(
        id = id, ruleCode = ruleCode, status = status.name, checkedCnt = checkedCnt,
        diffCnt = diffCnt, kdAbsorbedCnt = kdAbsorbedCnt, errorMsg = errorMsg, elapsedMs = elapsedMs,
    )

    private fun ReconResultDetailEntity.toResponse(ruleCode: String) = ReconDetailResponse(
        id = id, ruleCode = ruleCode, symbol = symbol, fieldName = fieldName,
        diffType = diffType.name, internalValue = internalValue, externalValue = externalValue,
        diffValue = diffValue, extras = extras, kdId = kdId,
    )
}

data class ExecuteRunRequest(val runDate: LocalDate, val runType: RunType = RunType.ALL)

data class ReconRunResponse(
    val id: UUID,
    val runDate: String,
    val runType: String,
    val status: String,
    val triggerType: String,
    val internalAsOf: String?,
    val externalAsOf: String?,
    val startedAt: String,
    val finishedAt: String?,
)

data class ReconSummaryResponse(
    val id: UUID,
    val ruleCode: String,
    val status: String,
    val checkedCnt: Int,
    val diffCnt: Int,
    val kdAbsorbedCnt: Int,
    val errorMsg: String?,
    val elapsedMs: Long,
)

data class ReconRunDetailResponse(
    val run: ReconRunResponse,
    val summaries: List<ReconSummaryResponse>,
)

data class ReconDetailResponse(
    val id: UUID,
    val ruleCode: String,
    val symbol: String?,
    val fieldName: String?,
    val diffType: String,
    val internalValue: java.math.BigDecimal?,
    val externalValue: java.math.BigDecimal?,
    val diffValue: java.math.BigDecimal?,
    val extras: String?,
    val kdId: UUID?,
)
