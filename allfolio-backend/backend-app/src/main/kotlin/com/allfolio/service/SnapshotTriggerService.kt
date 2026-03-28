package com.allfolio.service

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.api.portfolio.PortfolioSnapshotResponse
import com.allfolio.fx.CurrencyConverter
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.snapshot.application.GenerateDailySnapshotUseCase
import com.allfolio.snapshot.application.GenerateSnapshotCommand
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Snapshot 생성 공통 서비스
 *
 * TradeEventListener(실시간)와 OutboxEventProcessor(안전망) 양쪽에서 사용.
 * - 시장가: 이벤트 가격 + trade_raw 이력 중 자산별 최신가 → KRW 환산
 * - 전일 컨텍스트: performance_daily 조회
 * - @Transactional 없음 — GenerateDailySnapshotUseCase 의 트랜잭션에 위임
 */
@Service
class SnapshotTriggerService(
    private val tradeRepository: TradeRawJpaRepository,
    private val performanceRepository: PerformanceDailyJpaRepository,
    private val generateDailySnapshotUseCase: GenerateDailySnapshotUseCase,
    private val snapshotCache: SnapshotCacheRepository,
    private val metrics: BrokerMetrics,
    private val currencyConverter: CurrencyConverter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param currentPrices 이벤트/외부에서 제공된 최신 가격 (있으면 trade_raw 이력보다 우선, KRW 기준)
     * @return null if no trades/prices available
     */
    fun trigger(
        tenantId: UUID,
        portfolioId: UUID,
        tradeDate: LocalDate,
        currentPrices: Map<UUID, BigDecimal> = emptyMap(),
    ): PerformanceDailyEntity? {
        val cutoff = tradeDate.atTime(23, 59, 59)

        // ── 시장가 구성: trade_raw 이력 + KRW 환산 ────────────────────
        // 자산별 최신 거래가를 tradeCurrency → KRW 로 환산
        val historicalPrices = tradeRepository
            .findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(portfolioId, cutoff)
            .groupBy { it.assetId }
            .mapValues { (_, trades) ->
                val last = trades.last()
                currencyConverter.toKrw(last.price, last.tradeCurrency)
            }

        val marketPrices = historicalPrices + currentPrices  // currentPrices 우선 (이미 KRW 기준)

        if (marketPrices.isEmpty()) {
            log.warn("[Trigger] no market prices — skip portfolio={} date={}", portfolioId, tradeDate)
            return null
        }

        // ── 전일 컨텍스트 ─────────────────────────────────────────────
        val prevPerf = performanceRepository
            .findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(portfolioId, tradeDate)

        val recentReturns = if (prevPerf != null) {
            performanceRepository
                .findByIdPortfolioIdAndIdDateBetween(portfolioId, tradeDate.minusDays(30), tradeDate.minusDays(1))
                .map { it.dailyReturn }
        } else emptyList()

        // ── Snapshot 생성 (@Transactional in UseCase — 여기서는 비트랜잭션) ─
        val command = GenerateSnapshotCommand(
            tenantId                 = tenantId,
            portfolioId              = portfolioId,
            date                     = tradeDate,
            marketPrices             = marketPrices,
            yesterdayNav             = prevPerf?.nav ?: BigDecimal.ZERO,
            previousCumulativeReturn = prevPerf?.cumulativeReturn,
            recentDailyReturns       = recentReturns,
        )

        val (performance, risk) = metrics.recordSnapshotLatency { generateDailySnapshotUseCase.generate(command) }

        // ── Cache: @Transactional 커밋 후 실행 (UseCase 반환 = 커밋 완료) ─
        val response = PortfolioSnapshotResponse.of(performance, risk)
        snapshotCache.evict(tenantId, portfolioId, tradeDate)
        snapshotCache.saveLatest(tenantId, portfolioId, response)

        log.info("[Trigger] done nav={} date={} portfolio={}", performance.nav, tradeDate, portfolioId)
        return performance
    }
}
