package com.allfolio.api.portfolio

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.fx.CurrencyConverter
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.ReturnsCalculator
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import com.allfolio.unifiedasset.application.usecase.FlowAdjustedRiskSeries
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/portfolios")
class PortfolioSnapshotQueryController(
    private val performanceRepository: PerformanceDailyJpaRepository,
    private val tradeRepository: TradeRawJpaRepository,
    private val currencyConverter: CurrencyConverter,
    private val snapshotCache: SnapshotCacheRepository,
) {
    /**
     * GET /api/portfolios/{id}/snapshot/{date}?tenantId=...
     *
     * Cache-Aside:
     * 1. Redis 조회 → hit 시 즉시 반환
     * 2. miss → DB 조회 → Redis 저장 → 반환
     * 3. Redis 장애 → DB 결과 그대로 반환 (fallback)
     */
    @GetMapping("/{id}/snapshot/{date}")
    fun getSnapshot(
        @PathVariable id: UUID,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestHeader("X-User-Id") userId: UUID,
    ): ResponseEntity<PortfolioSnapshotResponse> {
        val tenantId = userId

        snapshotCache.getSnapshot(tenantId, id, date)?.let {
            return ResponseEntity.ok(it)
        }

        val performance = performanceRepository.findByPortfolioAndDateAndTenant(id, date, tenantId)
            ?: return ResponseEntity.notFound().build()

        val response = adjusted(performance)
        snapshotCache.saveSnapshot(tenantId, id, date, response)
        return ResponseEntity.ok(response)
    }

    /**
     * GET /api/portfolios/{id}/snapshot/latest?tenantId=...
     */
    @GetMapping("/{id}/snapshot/latest")
    fun getLatestSnapshot(
        @PathVariable id: UUID,
        @RequestHeader("X-User-Id") userId: UUID,
    ): ResponseEntity<PortfolioSnapshotResponse> {
        val tenantId = userId

        snapshotCache.getLatest(tenantId, id)?.let {
            return ResponseEntity.ok(it)
        }

        val performance = performanceRepository.findTopByIdTenantIdAndIdPortfolioIdOrderByIdDateDesc(tenantId, id)
            ?: return ResponseEntity.notFound().build()

        val response = adjusted(performance)
        snapshotCache.saveLatest(tenantId, id, response)
        return ResponseEntity.ok(response)
    }

    /**
     * 그날의 일간 수익률·리스크를 NAV + 매매 대금 플로우에서 읽는 시점에 계산한다 —
     * `performance_daily.daily_return`·`risk_daily`를 쓰지 않는다.
     *
     * 저장된 daily_return은 `(NAV − 전일 NAV) / 전일 NAV`인데 이 포트폴리오엔 현금이 없어 매수일이 수익,
     * 매도일이 손실로 잡힌다. risk_daily는 그 값을 먹은 결과다. 플로우 정의는 [TradeFlows].
     *
     * - 일간 수익률: 그날로 끝나는 구간 수익률([ReturnsCalculator.segmentReturns]) — 리스크가 체인링킹하는 바로 그 값.
     *   첫 관측일·분모 ≤ 0인 날은 구간이 없어 null이다(저장값은 첫날 0이었다).
     * - 알파: 저장값이 `저장 daily_return − 벤치마크`라 같이 다시 뺀다. 안 그러면 옆 칸의 수익률과 안 맞는다.
     * - 리스크: B-04·대시보드와 같은 [FlowAdjustedRiskSeries]. 구간이 2건 미만인 날은 null
     *   — 한 건으로는 변동성이 0으로 나와 "위험 없음"으로 읽힌다.
     */
    private fun adjusted(performance: PerformanceDailyEntity): PortfolioSnapshotResponse {
        val (tenantId, portfolioId, date) = Triple(performance.id.tenantId, performance.id.portfolioId, performance.id.date)
        val navs = performanceRepository
            .findByIdPortfolioIdAndIdDateBetween(portfolioId, EARLIEST, date)
            .filter { it.id.tenantId == tenantId }
            .map { NavPoint(it.id.date, it.nav) }
        val trades = tradeRepository
            .findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(portfolioId, date.atTime(23, 59, 59))
        val flows = TradeFlows.of(trades, currencyConverter::toKrw)

        val dailyReturn = ReturnsCalculator.segmentReturns(navs, flows)
            .lastOrNull()?.takeIf { it.date == date }
            ?.ratio?.setScale(RETURN_SCALE, RoundingMode.HALF_UP)
        val risk = FlowAdjustedRiskSeries.build(navs, flows).lastOrNull()?.takeIf { it.date == date }
        return PortfolioSnapshotResponse.of(performance, dailyReturn, risk)
    }

    companion object {
        /** 전체 시계열 로드 시작일 — GetDashboardUseCase와 같은 관례 */
        private val EARLIEST: LocalDate = LocalDate.of(2000, 1, 1)

        /** performance_daily.daily_return 저장 스케일(DailyPerformanceEngine)과 같게 */
        private const val RETURN_SCALE = 10
    }
}
