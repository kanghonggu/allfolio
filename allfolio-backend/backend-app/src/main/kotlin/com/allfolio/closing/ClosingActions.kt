package com.allfolio.closing

import com.allfolio.reconciliation.application.ReconRunService
import com.allfolio.reconciliation.application.SyncInProgressException
import com.allfolio.reconciliation.domain.ReconTrigger
import com.allfolio.reconciliation.domain.RunStatus
import com.allfolio.reconciliation.domain.RunType
import com.allfolio.report.application.GenerateReportUseCase
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.usecase.DailyAccountSyncer
import com.allfolio.unifiedasset.application.usecase.DailyNavScheduler
import com.allfolio.workflow.application.WfAction
import com.allfolio.workflow.application.WfActionResult
import com.allfolio.workflow.application.WfContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 마감 워크플로우 액션 (P3 #24) — 시드 wf_sub_step.action_ref와 매칭되는 코드 빈.
 * 기존 배치 로직을 편입: 각 액션은 멱등(재작업 차수 재실행 안전).
 * 전 사용자 루프는 사용자별 runCatching 격리 — 일부 실패는 요약에 집계하고 전체는 계속.
 */

/** 데이터가 있는 사용자 목록 (ua_accounts 보유 기준). */
@Component
class ClosingUserSource(private val jdbc: JdbcTemplate) {
    fun activeUserIds(): List<UUID> =
        jdbc.query("SELECT DISTINCT user_id FROM ua_accounts") { rs, _ ->
            rs.getObject("user_id", UUID::class.java)
        }
}

/** S010 — 전 계좌 재동기화 (구 DailyNavScheduler 1단계). */
@Component
class SyncAllAccountsAction(
    private val dailyAccountSyncer: DailyAccountSyncer,
) : WfAction {
    override val ref = "SYNC_ALL_ACCOUNTS"
    override fun execute(ctx: WfContext): WfActionResult {
        val result = dailyAccountSyncer.syncAll()
        // 전 계좌 실패는 이후 단계 데이터 신선도가 보장 안 됨 — ERROR로 표면화
        if (result.total > 0 && result.synced == 0) {
            error("전 계좌 동기화 실패: failed=${result.failed}/${result.total}")
        }
        return WfActionResult("synced=${result.synced} failed=${result.failed} total=${result.total}")
    }
}

/** S020/S040 공용 — 전 사용자 대사 실행. 사용자 recon 락 경합은 skip 집계. */
abstract class AllUsersReconAction(
    private val reconRunService: ReconRunService,
    private val userSource: ClosingUserSource,
    private val runType: RunType,
) : WfAction {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(ctx: WfContext): WfActionResult {
        var ok = 0; var failed = 0; var skipped = 0
        userSource.activeUserIds().forEach { userId ->
            runCatching { reconRunService.execute(userId, ctx.ymd, runType, ReconTrigger.SCHEDULED) }
                .onSuccess { run -> if (run.status == RunStatus.COMPLETED) ok++ else failed++ }
                .onFailure { e ->
                    when (e) {
                        is SyncInProgressException -> skipped++
                        else -> { failed++; log.error("[Closing] recon 실패 userId={} type={}", userId, runType, e) }
                    }
                }
        }
        if (ok == 0 && failed > 0) error("전 사용자 $runType 실패: failed=$failed skipped=$skipped")
        return WfActionResult("users ok=$ok failed=$failed skipped=$skipped")
    }
}

/** S020 — 검증 룰 실행(전 사용자). */
@Component
class ReconValidationAction(
    reconRunService: ReconRunService, userSource: ClosingUserSource,
) : AllUsersReconAction(reconRunService, userSource, RunType.VALIDATION) {
    override val ref = "RECON_VALIDATION"
}

/** S040 — 포지션 대사(전 사용자). */
@Component
class ReconPositionAction(
    reconRunService: ReconRunService, userSource: ClosingUserSource,
) : AllUsersReconAction(reconRunService, userSource, RunType.RECONCILIATION) {
    override val ref = "RECON_POSITION"
}

/** S030 — NAV 스냅샷(전 사용자, 구 DailyNavScheduler 2단계). */
@Component
class NavSnapshotAction(
    private val dailyNavScheduler: DailyNavScheduler,
) : WfAction {
    override val ref = "NAV_SNAPSHOT"
    // ctx.ymd를 흘려보낸다 — 여기서 LocalDate.now()를 쓰면 워크플로우가 정한 날과
    // 데이터가 갈라진다(컨테이너 UTC, 자정 KST = UTC 전날 15:00)
    override fun execute(ctx: WfContext): WfActionResult =
        WfActionResult("snapshots=${dailyNavScheduler.recordDailySnapshots(ctx.ymd)}")
}

/** S060 — 월간 리포트 아카이브 생성(전 사용자 × 전 유형, 실행일이 속한 달). */
@Component
class MonthlyReportsAction(
    private val generateReport: GenerateReportUseCase,
    private val userSource: ClosingUserSource,
) : WfAction {
    private val log = LoggerFactory.getLogger(javaClass)
    override val ref = "MONTHLY_REPORTS"

    override fun execute(ctx: WfContext): WfActionResult {
        val period = ReportPeriod.monthly(ctx.ymd.year, ctx.ymd.monthValue)
        var ok = 0; var failed = 0
        userSource.activeUserIds().forEach { userId ->
            ReportType.entries.forEach { type ->
                runCatching { generateReport.generate(userId, type, period) }
                    .onSuccess { ok++ }
                    .onFailure { e ->
                        failed++
                        log.error("[Closing] 리포트 생성 실패 userId={} type={}", userId, type, e)
                    }
            }
        }
        if (ok == 0 && failed > 0) error("월간 리포트 전건 실패: failed=$failed")
        return WfActionResult("reports ok=$ok failed=$failed (${period.start}~${period.end})")
    }
}
