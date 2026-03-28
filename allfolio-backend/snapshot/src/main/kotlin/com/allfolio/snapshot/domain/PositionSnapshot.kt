package com.allfolio.snapshot.domain

import java.math.BigDecimal
import java.util.UUID

/**
 * 특정 시점의 단일 자산 포지션 계산 결과.
 * 불변 객체 — 생성 후 수정 불가.
 */
data class PositionSnapshot(
    val assetId: UUID,
    val totalQuantity: BigDecimal,
    val averageCost: BigDecimal,
    val realizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal,
) {
    val totalPnl: BigDecimal
        get() = realizedPnl.add(unrealizedPnl)

    fun hasPosition(): Boolean = totalQuantity.compareTo(BigDecimal.ZERO) > 0

    companion object {
        fun empty(assetId: UUID): PositionSnapshot = PositionSnapshot(
            assetId        = assetId,
            totalQuantity  = BigDecimal.ZERO,
            averageCost    = BigDecimal.ZERO,
            realizedPnl    = BigDecimal.ZERO,
            unrealizedPnl  = BigDecimal.ZERO,
        )
    }
}
