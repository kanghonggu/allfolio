package com.allfolio.trade.domain

import java.math.BigDecimal

/**
 * FIFO 원가 계산 엔진 (순수 도메인 서비스)
 *
 * - 상태 없음, side-effect 없음, DB/직렬화 의존 없음
 * - BUY: lot 추가. SELL: FIFO 소진 + 실현손익 누적.
 * - 초과매도(SELL > 보유): 보유분까지만 소진(clamp), 예외 없음.
 *   초과매도를 거부하려는 호출자는 apply 전에 사전검증한다.
 * - trades는 executedAt 오름차순 정렬 가정.
 */
object FifoCostEngine {

    /** 거래 1건을 현재 포지션에 반영한 새 포지션을 반환한다. */
    fun apply(
        position: LotPosition,
        tradeType: TradeType,
        quantity: BigDecimal,
        price: BigDecimal,
        fee: BigDecimal = BigDecimal.ZERO,
    ): LotPosition = when (tradeType) {
        TradeType.BUY  -> position.copy(lots = position.lots + CostLot(price, quantity))
        TradeType.SELL -> sell(position, quantity, price, fee)
    }

    /** 거래 목록을 EMPTY 포지션부터 순서대로 재생한다. */
    fun replay(trades: List<TradeRaw>): LotPosition =
        trades.fold(LotPosition.EMPTY) { pos, t ->
            apply(pos, t.tradeType, t.quantity, t.price, t.fee)
        }

    private fun sell(
        position: LotPosition,
        sellQty: BigDecimal,
        sellPrice: BigDecimal,
        fee: BigDecimal,
    ): LotPosition {
        var remaining = sellQty
        var consumedCost = BigDecimal.ZERO
        val newLots = ArrayList<CostLot>(position.lots.size)

        for (lot in position.lots) {
            if (remaining.signum() <= 0) {
                newLots.add(lot)
                continue
            }
            val consumed = remaining.min(lot.quantity)
            consumedCost = consumedCost + consumed * lot.unitPrice
            remaining = remaining - consumed
            val leftover = lot.quantity - consumed
            if (leftover.signum() > 0) newLots.add(CostLot(lot.unitPrice, leftover))
        }

        val soldQty = sellQty - remaining
        val realized = soldQty * sellPrice - consumedCost - fee
        return LotPosition(newLots, position.realizedPnl + realized)
    }
}
