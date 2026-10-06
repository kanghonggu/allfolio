package com.allfolio.api.portfolio

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.fx.CurrencyConverter
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import com.allfolio.unifiedasset.application.usecase.DailyRisk
import com.allfolio.unifiedasset.application.usecase.FlowAdjustedRiskSeries
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
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

        val response = PortfolioSnapshotResponse.of(performance, riskAt(performance))
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

        val response = PortfolioSnapshotResponse.of(performance, riskAt(performance))
        snapshotCache.saveLatest(tenantId, id, response)
        return ResponseEntity.ok(response)
    }

    /**
     * 그날의 리스크를 NAV + 매매 대금 플로우에서 읽는 시점에 계산한다 — risk_daily를 쓰지 않는다.
     *
     * risk_daily는 `performance_daily.daily_return = (NAV − 전일 NAV) / 전일 NAV`를 먹는데, 이
     * 포트폴리오엔 현금이 없어 매수일이 수익, 매도일이 손실로 잡힌다. 플로우 정의는 [TradeFlows],
     * 지표 계산·창(30일)은 B-04·대시보드와 같은 [FlowAdjustedRiskSeries].
     *
     * 구간 수익률이 2건 미만인 날은 null — 한 건으로는 변동성이 0으로 나와 "위험 없음"으로 읽힌다.
     */
    private fun riskAt(performance: PerformanceDailyEntity): DailyRisk? {
        val (tenantId, portfolioId, date) = Triple(performance.id.tenantId, performance.id.portfolioId, performance.id.date)
        val navs = performanceRepository
            .findByIdPortfolioIdAndIdDateBetween(portfolioId, EARLIEST, date)
            .filter { it.id.tenantId == tenantId }
            .map { NavPoint(it.id.date, it.nav) }
        val trades = tradeRepository
            .findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(portfolioId, date.atTime(23, 59, 59))
        val flows = TradeFlows.of(trades, currencyConverter::toKrw)
        return FlowAdjustedRiskSeries.build(navs, flows).lastOrNull()?.takeIf { it.date == date }
    }

    companion object {
        /** 전체 시계열 로드 시작일 — GetDashboardUseCase와 같은 관례 */
        private val EARLIEST: LocalDate = LocalDate.of(2000, 1, 1)
    }
}
