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

/**
 * S030 — NAV 스냅샷(전 사용자, 구 DailyNavScheduler 2단계).
 *
 * **`ctx.ymd`가 아니라 `ctx.ymd - 1일`로 기록한다.** 이 워크플로우는 KST 자정에 뜨고
 * `ctx.ymd`는 **실행일**이다(`ClosingScheduler`가 `LocalDate.now(KST)`로 구한다). 그 시점에
 * 읽히는 자산은 아직 시작도 안 한 실행일이 아니라 **직전 영업일이 끝난 값**이다. 실행일로
 * 라벨하면 D 행에 D−1의 값이 앉는다.
 *
 * 이게 왜 위험한가 — **어긋나도 화면에 아무 신호가 안 뜬다:**
 * - `ReportService.buildBenchmarkSeries`는 exact join이 아니라 as-of 조회다
 *   (`rows.lastOrNull { it.first <= date }`). null도 구멍도 안 생기고 D의 지수 종가와
 *   D−1의 포트폴리오 값이 조용히 짝지어져, 포트폴리오가 지수를 하루 늦게 따라가는 것처럼 그려진다.
 * - `GetReturnsAnalysisUseCase`는 `[from, to]` 양 끝 종가를 쓴다. NAV가 하루 밀리면 포트폴리오의
 *   실제 측정 구간이 `[from−1, to−1]`이 되어 초과수익이 하루치 시장 움직임만큼 틀린다.
 *
 * UPSERT 키가 `(tenant, portfolio, date)`인 것도 같은 방향을 가리킨다. 실행일로 라벨하면
 * 자정이 쓴 행을 그날 낮 동기화가 덮어써서 **D 행의 의미가 "그날 사용자가 동기화했는지"에 따라
 * 달라진다.** 직전일로 라벨하면 자정 실행이 그 날짜의 마지막 기록자가 되어 확정값이 된다.
 * `daily_return`이 직전 행 대비라 그 비결정성은 수익률까지 간다.
 *
 * **`ctx.ymd`의 의미는 건드리지 않는다.** 워크플로우 전체에서 그건 실행일이고 `S060`
 * (`ReportPeriod.monthly(ctx.ymd.year, ctx.ymd.monthValue)`)이 그 의미에 의존한다.
 * 여기서 고치는 것은 NAV 행의 라벨 하나뿐이다.
 */
@Component
class NavSnapshotAction(
    private val dailyNavScheduler: DailyNavScheduler,
) : WfAction {
    override val ref = "NAV_SNAPSHOT"

    override fun execute(ctx: WfContext): WfActionResult =
        WfActionResult("snapshots=${dailyNavScheduler.recordDailySnapshots(ctx.ymd.minusDays(1))}")
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
