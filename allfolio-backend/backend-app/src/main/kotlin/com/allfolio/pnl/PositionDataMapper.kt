package com.allfolio.pnl

import com.allfolio.trade.domain.CostLot
import com.allfolio.trade.domain.LotPosition
import java.math.BigDecimal
import java.util.UUID

/**
 * Redis 직렬화용 PositionData ↔ 순수 코어 LotPosition 변환.
 *
 * 레거시 shim: lots 없이 quantity만 있는 옛 캐시 데이터는
 * (avgCost, quantity) 단일 lot으로 합성해 수량 손실을 막는다.
 */
object PositionDataMapper {

    fun toLotPosition(data: PositionData): LotPosition {
        val lots: List<CostLot> = when {
            data.lots.isNotEmpty() -> data.lots.map { CostLot(it.price, it.quantity) }
            data.quantity.signum() > 0 -> listOf(CostLot(data.avgCost, data.quantity)) // 레거시 합성
            else -> emptyList()
        }
        return if (lots.isEmpty()) LotPosition.EMPTY else LotPosition(lots, BigDecimal.ZERO)
    }

    fun toPositionData(
        position: LotPosition,
        portfolioId: UUID,
        assetId: UUID,
        currency: String,
    ): PositionData = PositionData(
        portfolioId = portfolioId,
        assetId     = assetId,
        quantity    = position.totalQuantity,
        avgCost     = position.averageCost,
        currency    = currency,
        lots        = position.lots.map { PositionLot(price = it.unitPrice, quantity = it.quantity) },
    )
}
