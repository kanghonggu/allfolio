package com.allfolio.trade.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * FIFO 원가 포지션 — lots(오래된 것 앞) + 누적 실현손익. 불변.
 * FifoCostEngine이 생성/갱신한다.
 */
data class LotPosition(
    val lots: List<CostLot>,
    val realizedPnl: BigDecimal,
) {
    val totalQuantity: BigDecimal
        get() = lots.fold(BigDecimal.ZERO) { acc, lot -> acc + lot.quantity }

    /** 잔여 lots 가중평균 단가 (scale 10, HALF_UP). lots 비면 ZERO. */
    val averageCost: BigDecimal
        get() {
            val qty = totalQuantity
            if (qty.signum() == 0) return BigDecimal.ZERO
            val cost = lots.fold(BigDecimal.ZERO) { acc, lot -> acc + lot.unitPrice * lot.quantity }
            return cost.divide(qty, SCALE, ROUNDING)
        }

    /** 가장 오래된 lot의 단가 (FIFO 원가). lots 비면 null. */
    val fifoCostBasis: BigDecimal?
        get() = lots.firstOrNull()?.unitPrice

    companion object {
        val EMPTY = LotPosition(emptyList(), BigDecimal.ZERO)
        private const val SCALE = 10
        private val ROUNDING = RoundingMode.HALF_UP
    }
}
