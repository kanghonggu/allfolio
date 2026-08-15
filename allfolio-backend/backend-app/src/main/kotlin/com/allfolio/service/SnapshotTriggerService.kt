package com.allfolio.service

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.api.portfolio.PortfolioSnapshotResponse
import com.allfolio.fx.CurrencyConverter
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.snapshot.NativePrice
import com.allfolio.snapshot.NavCurrencyDailyStore
import com.allfolio.snapshot.application.GenerateDailySnapshotUseCase
import com.allfolio.snapshot.application.GenerateSnapshotCommand
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.PositionDailyJpaRepository
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
    private val positionRepository: PositionDailyJpaRepository,
    private val navCurrencyStore: NavCurrencyDailyStore,
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
        //
        // AF-106: 환산 전 원통화 시세를 같이 남긴다. 이 줄 아래로는 전부 원화라
        // 통화를 아는 자리가 여기뿐이다 — 여기서 안 잡으면 영영 복원 못 한다.
        val lastTrades = tradeRepository
            .findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(portfolioId, cutoff)
            .groupBy { it.assetId }
            .mapValues { (_, trades) -> trades.last() }

        val nativePrices = lastTrades.mapValues { (_, t) -> NativePrice(t.price, t.tradeCurrency) }
        val historicalPrices = lastTrades.mapValues { (_, t) -> currencyConverter.toKrw(t.price, t.tradeCurrency) }

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

        // ── AF-106 통화별 평가액 ────────────────────────────────────────
        // **실패가 스냅샷을 되돌리면 안 된다.** NAV는 핵심이고 통화 분해는 부가 기능이다.
        // generate()는 이미 커밋됐으므로(클래스 KDoc) position_daily를 읽는 건 안전하다.
        // 행이 없는 날은 조회 쪽 노출 조건이 알아서 블록을 숨긴다.
        //
        // currentPrices로 덮인 자산은 이미 KRW라 원통화를 복원할 수 없어 여기서 빠진다.
        // 그 수를 세어 로그로 남긴다 — 조용히 넘기면 "환율 기여가 왜 이렇게 작냐"에
        // 답할 근거가 없어진다.
        try {
            val quantities = positionRepository
                .findByIdPortfolioIdAndIdDate(portfolioId, tradeDate)
                .associate { it.id.assetId to it.quantity }
            val values = NavCurrencyDailyStore.aggregate(quantities, nativePrices) { code ->
                currencyConverter.sourceOf(code)?.rate ?: BigDecimal.ONE
            }
            navCurrencyStore.replace(portfolioId, tradeDate, values)
            val approximated = quantities.keys.count { it !in nativePrices }
            if (approximated > 0) {
                log.info(
                    "[NavCurrency] {} assets had no native price (KRW-only path) portfolio={} date={}",
                    approximated, portfolioId, tradeDate,
                )
            }
        } catch (e: Exception) {
            log.warn(
                "[NavCurrency] write failed — snapshot is intact, attribution will be hidden. portfolio={} date={}: {}",
                portfolioId, tradeDate, e.message,
            )
        }

        // ── Cache: @Transactional 커밋 후 실행 (UseCase 반환 = 커밋 완료) ─
        val response = PortfolioSnapshotResponse.of(performance, risk)
        snapshotCache.evict(tenantId, portfolioId, tradeDate)
        snapshotCache.saveLatest(tenantId, portfolioId, response)

        log.info("[Trigger] done nav={} date={} portfolio={}", performance.nav, tradeDate, portfolioId)
        return performance
    }
}
