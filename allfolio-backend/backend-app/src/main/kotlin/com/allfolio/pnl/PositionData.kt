package com.allfolio.pnl

import java.math.BigDecimal
import java.util.UUID

/**
 * 포지션 데이터 (Redis Hash 저장 단위)
 *
 * Redis key:   pnl:positions:{portfolioId}
 * Redis field: {assetId}
 * Redis value: JSON(PositionData)
 *
 * avgCost: 매수 평균 단가 (FIFO 아닌 평균가 방식)
 * quantity: 현재 보유 수량 (SELL 시 차감)
 */
data class PositionData(
    val portfolioId: UUID,
    val assetId: UUID,
    val quantity: BigDecimal,
    /** 매수 가중평균 단가 (lots 에서 자동 계산됨) */
    val avgCost: BigDecimal,
    /** 매수 시 통화 (KRW | USDT) — KRW 환산값 계산에 사용 */
    val currency: String = "KRW",
    /**
     * FIFO Lot 리스트 — BUY 순서 오름차순.
     * SELL 시 앞에서부터 소진된다.
     * 기본값 emptyList(): 기존 Redis 데이터 역직렬화 호환성 유지.
     */
    val lots: List<PositionLot> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 실시간 PnL 계산 결과
 * PortfolioUpdateEvent에 포함되어 발행됨
 */
data class PortfolioUpdateEvent(
    val portfolioId: UUID,
    val assetId: UUID,
    val symbol: String,
    val quantity: BigDecimal,
    val avgCost: BigDecimal,
    val currentPrice: BigDecimal,
    val unrealizedPnl: BigDecimal,          // (currentPrice - avgCost) * quantity
    val unrealizedPnlPct: BigDecimal,       // unrealizedPnl / (avgCost * quantity) * 100
    val timestamp: Long = System.currentTimeMillis(),
)
