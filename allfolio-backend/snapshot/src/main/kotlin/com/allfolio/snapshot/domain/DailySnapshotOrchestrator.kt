package com.allfolio.snapshot.domain

import com.allfolio.risk.domain.RiskEngine
import com.allfolio.trade.domain.TradeRaw
import java.math.BigDecimal
import java.util.UUID

/**
 * 일간 스냅샷 조합 도메인 서비스
 *
 * 역할:
 *   PositionEngine + DailyPerformanceEngine + RiskEngine 을 단일 계산 흐름으로 조합한다.
 *
 * 원칙:
 *   - DB 접근 없음
 *   - side-effect 없음
 *   - 계산 위임만 수행
 *   - 순수 도메인 orchestration
 */
object DailySnapshotOrchestrator {

    /**
     * @param tradesByAsset           자산별 거래 이력 (시간순 정렬 보장)
     * @param marketPrices            자산별 현재 시장 가격 (assetId → price)
     * @param yesterdayNav            전일 NAV
     * @param externalCashFlow        당일 외부 입출금 (입금 양수, 출금 음수)
     * @param previousCumulativeReturn 전일까지 누적 수익률 (첫날이면 null)
     * @param benchmarkReturn         당일 벤치마크 수익률 (없으면 null)
     * @param recentDailyReturns      최근 N일 수익률 리스트 (리스크 계산용, 시간순)
     */
    fun generate(
        tradesByAsset: Map<UUID, List<TradeRaw>>,
        marketPrices: Map<UUID, BigDecimal>,
        yesterdayNav: BigDecimal,
        externalCashFlow: BigDecimal = BigDecimal.ZERO,
        previousCumulativeReturn: BigDecimal? = null,
        benchmarkReturn: BigDecimal? = null,
        recentDailyReturns: List<BigDecimal> = emptyList(),
    ): DailySnapshotResult {

        // ── Step 1: 자산별 포지션 계산 ──────────────────────────────
        val positions = computePositions(tradesByAsset, marketPrices)

        // ── Step 2: 오늘 NAV = Σ(quantity × marketPrice) ──────────
        val todayNav = computeNav(positions, marketPrices)

        // ── Step 3: 일간 성과 계산 ──────────────────────────────────
        val performance = DailyPerformanceEngine.calculate(
            todayNav                 = todayNav,
            yesterdayNav             = yesterdayNav,
            externalCashFlow         = externalCashFlow,
            previousCumulativeReturn = previousCumulativeReturn,
            benchmarkReturn          = benchmarkReturn,
        )

        // ── Step 4: 리스크 계산 ─────────────────────────────────────
        val risk = if (recentDailyReturns.isEmpty()) {
            com.allfolio.risk.domain.RiskSnapshot.empty()
        } else {
            RiskEngine.calculate(recentDailyReturns)
        }

        return DailySnapshotResult(
            positions   = positions,
            performance = performance,
            risk        = risk,
        )
    }

    // ──────────────────────────────────────────────
    // private helpers
    // ──────────────────────────────────────────────

    private fun computePositions(
        tradesByAsset: Map<UUID, List<TradeRaw>>,
        marketPrices: Map<UUID, BigDecimal>,
    ): List<PositionSnapshot> {
        if (tradesByAsset.isEmpty()) return emptyList()

        return tradesByAsset.map { (assetId, trades) ->
            val marketPrice = marketPrices[assetId]
                ?: throw OrchestratorException.missingMarketPrice(assetId)
            PositionEngine.calculate(trades, marketPrice)
        }
    }

    /**
     * NAV = Σ(position.totalQuantity × marketPrice)
     * unrealizedPnl은 이미 position에 포함되어 있으므로
     * 시장가치(qty × price)를 직접 합산한다.
     */
    private fun computeNav(
        positions: List<PositionSnapshot>,
        marketPrices: Map<UUID, BigDecimal>,
    ): BigDecimal {
        return positions.fold(BigDecimal.ZERO) { acc, pos ->
            val price = marketPrices[pos.assetId] ?: BigDecimal.ZERO
            acc.add(pos.totalQuantity.multiply(price))
        }
    }
}
