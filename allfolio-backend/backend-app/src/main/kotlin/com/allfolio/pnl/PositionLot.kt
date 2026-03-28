package com.allfolio.pnl

import java.math.BigDecimal

/**
 * 포지션 Lot — BUY 1건 = Lot 1개
 *
 * Redis 직렬화를 위해 data class (Jackson).
 * PositionData.lots 에 순서대로 저장된다 (BUY 시각 오름차순).
 *
 * SELL FIFO: lots 앞에서부터 소진.
 * SELL AvgCost: lots 비례 차감 (totalQuantity 기준).
 *
 * [snapshot 모듈의 PositionLot 과는 별개]
 *   - snapshot.PositionLot: 도메인 계산용, immutable, remainingQuantity tracked
 *   - pnl.PositionLot:      Redis 캐시용, mutable quantity, JSON 직렬화 우선
 */
data class PositionLot(
    val price: BigDecimal,
    var quantity: BigDecimal,
    val purchasedAt: Long = System.currentTimeMillis(),
)
