package com.allfolio.snapshot.domain

import com.allfolio.trade.domain.TradeRaw
import com.allfolio.trade.domain.TradeType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque
import java.util.UUID

/**
 * FIFO 포지션 계산 엔진 (순수 도메인 서비스)
 *
 * 원칙:
 * - 상태 저장 없음
 * - side-effect 없음
 * - DB 접근 없음
 * - trades는 executedAt 기준 오름차순 정렬되어 있다고 가정
 *
 * 성능 개선 (v2):
 * - LinkedList → ArrayDeque (CPU 캐시 친화적, peekFirst/pollFirst O(1))
 * - available 사전 계산 제거 → totalHeld 누적으로 O(1) 검증
 * - BigDecimal.compareTo() → signum() (불필요한 scale 정규화 제거)
 * - 소진된 Lot 즉시 poll → filter() 루프 제거
 */
object PositionEngine {

    private val SCALE = 10
    private val ROUNDING = RoundingMode.HALF_UP

    fun calculate(
        trades: List<TradeRaw>,
        marketPrice: BigDecimal,
    ): PositionSnapshot {
        if (trades.isEmpty()) return PositionSnapshot.empty(extractAssetId(trades))

        val assetId   = trades.first().assetId
        val lotDeque  = ArrayDeque<PositionLot>(trades.size / 2 + 1)
        var realizedPnl  = BigDecimal.ZERO
        var totalHeld    = BigDecimal.ZERO   // 현재 보유 수량 누적 (available 계산 대체)

        for (trade in trades) {
            when (trade.tradeType) {
                TradeType.BUY -> {
                    lotDeque.addLast(PositionLot(trade.quantity, trade.price))
                    totalHeld = totalHeld.add(trade.quantity)
                }
                TradeType.SELL -> {
                    if (trade.quantity.compareTo(totalHeld) > 0) {
                        throw PositionException.insufficientQuantity(assetId, trade.quantity, totalHeld)
                    }
                    val pnl = processSell(lotDeque, trade.quantity, trade.price, trade.fee)
                    realizedPnl = realizedPnl.add(pnl)
                    totalHeld   = totalHeld.subtract(trade.quantity)
                }
            }
        }

        // 잔여 Lot 집계 (소진 Lot은 이미 poll로 제거됨)
        var totalQty  = BigDecimal.ZERO
        var totalCost = BigDecimal.ZERO
        for (lot in lotDeque) {
            totalQty  = totalQty.add(lot.remainingQuantity)
            totalCost = totalCost.add(lot.remainingQuantity.multiply(lot.unitPrice))
        }

        val averageCost   = if (totalQty.signum() == 0) BigDecimal.ZERO
                            else totalCost.divide(totalQty, SCALE, ROUNDING)
        val unrealizedPnl = marketPrice.subtract(averageCost).multiply(totalQty)

        return PositionSnapshot(
            assetId       = assetId,
            totalQuantity = totalQty.setScale(SCALE, ROUNDING),
            averageCost   = averageCost,
            realizedPnl   = realizedPnl.setScale(SCALE, ROUNDING),
            unrealizedPnl = unrealizedPnl.setScale(SCALE, ROUNDING),
        )
    }

    /**
     * SELL 처리: FIFO 순서로 Lot을 소진하고 실현손익을 반환한다.
     * 소진된 Lot은 Deque에서 즉시 제거한다.
     */
    private fun processSell(
        lotDeque: ArrayDeque<PositionLot>,
        sellQty: BigDecimal,
        sellPrice: BigDecimal,
        fee: BigDecimal,
    ): BigDecimal {
        var remaining = sellQty
        var costBasis = BigDecimal.ZERO

        while (remaining.signum() > 0) {
            val lot      = lotDeque.peekFirst()!!
            val consumed = lot.consume(remaining)
            costBasis    = costBasis.add(consumed.multiply(lot.unitPrice))
            remaining    = remaining.subtract(consumed)

            if (lot.isExhausted()) lotDeque.pollFirst()   // 소진된 Lot 즉시 제거
        }

        return sellQty.multiply(sellPrice).subtract(costBasis).subtract(fee)
    }

    private fun extractAssetId(trades: List<TradeRaw>): UUID =
        trades.firstOrNull()?.assetId ?: throw PositionException.emptyTrades()
}
