package com.allfolio.snapshot.domain

import java.math.BigDecimal

/**
 * FIFO 계산의 기본 단위.
 * BUY 1건 = Lot 1개.
 * SELL 시 가장 오래된 Lot부터 소진된다.
 */
class PositionLot(
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
) {
    var remainingQuantity: BigDecimal = quantity
        private set

    fun isExhausted(): Boolean = remainingQuantity.compareTo(BigDecimal.ZERO) == 0

    /**
     * 요청 수량만큼 Lot을 소진한다.
     *
     * @param requested 소진 요청 수량
     * @return 이 Lot에서 실제 소진된 수량 (요청보다 작을 수 있음)
     */
    fun consume(requested: BigDecimal): BigDecimal {
        if (requested < BigDecimal.ZERO) throw PositionException.negativeQuantity(requested)

        val consumed = requested.min(remainingQuantity)
        remainingQuantity = remainingQuantity.subtract(consumed)
        return consumed
    }

    override fun toString(): String =
        "PositionLot(unitPrice=$unitPrice, remaining=$remainingQuantity / total=$quantity)"
}
