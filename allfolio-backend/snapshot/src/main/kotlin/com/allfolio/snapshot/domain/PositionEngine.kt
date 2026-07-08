package com.allfolio.snapshot.domain

import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.domain.TradeRaw
import com.allfolio.trade.domain.TradeType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * FIFO 포지션 계산 엔진 (일별 스냅샷용).
 *
 * FIFO 원가·실현손익 계산은 공용 FifoCostEngine에 위임하고,
 * 이 엔진은 스냅샷 고유의 두 가지만 담당한다:
 *   1) 초과매도(SELL > 보유) 사전검증 → 데이터 오류로 거부 (정책)
 *   2) marketPrice 기반 미실현손익 계산
 */
object PositionEngine {

    private const val SCALE = 10
    private val ROUNDING = RoundingMode.HALF_UP

    fun calculate(
        trades: List<TradeRaw>,
        marketPrice: BigDecimal,
    ): PositionSnapshot {
        if (trades.isEmpty()) throw PositionException.emptyTrades()

        val assetId = trades.first().assetId

        // 초과매도 사전검증 — 스냅샷은 완전한 이력을 전제하므로 데이터 오류로 거부
        var held = BigDecimal.ZERO
        for (trade in trades) {
            when (trade.tradeType) {
                TradeType.BUY  -> held = held.add(trade.quantity)
                TradeType.SELL -> {
                    if (trade.quantity.compareTo(held) > 0) {
                        throw PositionException.insufficientQuantity(assetId, trade.quantity, held)
                    }
                    held = held.subtract(trade.quantity)
                }
            }
        }

        val position     = FifoCostEngine.replay(trades)
        val totalQty      = position.totalQuantity
        val averageCost   = position.averageCost
        val unrealizedPnl = marketPrice.subtract(averageCost).multiply(totalQty)

        return PositionSnapshot(
            assetId       = assetId,
            totalQuantity = totalQty.setScale(SCALE, ROUNDING),
            averageCost   = averageCost,
            realizedPnl   = position.realizedPnl.setScale(SCALE, ROUNDING),
            unrealizedPnl = unrealizedPnl.setScale(SCALE, ROUNDING),
        )
    }
}
