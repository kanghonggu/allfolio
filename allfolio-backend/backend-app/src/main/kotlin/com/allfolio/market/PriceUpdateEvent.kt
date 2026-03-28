package com.allfolio.market

import java.math.BigDecimal
import java.util.UUID

/**
 * 실시간 시세 업데이트 이벤트
 *
 * ApplicationEventPublisher로 발행 → @Async @EventListener로 수신
 * assetId: BinanceTradeMapper.assetId(symbol) 로 결정론적 변환
 */
data class PriceUpdateEvent(
    val exchange: String,
    val symbol: String,
    val assetId: UUID,
    val price: BigDecimal,
    val timestamp: Long,
)
