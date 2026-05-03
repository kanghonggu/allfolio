package com.allfolio.marketdata.kafka

import java.math.BigDecimal

/**
 * Kafka market.prices 토픽 메시지 DTO
 *
 * Key:   "{exchange}:{symbol}"  ex) "BINANCE:BTCUSDT", "KIS:005930"
 * Value: JSON(MarketPriceEvent)
 *
 * Key 설계:
 *   같은 exchange:symbol은 같은 파티션 → symbol별 순서 보장
 *   12개 파티션에 exchange:symbol 해시로 자동 분배
 */
data class MarketPriceEvent(
    val exchange:  String,       // "BINANCE" | "KIS"
    val symbol:    String,       // "BTCUSDT" | "005930"
    val assetId:   String,       // UUID string — 결정론적 변환
    val price:     BigDecimal,
    val volume:    BigDecimal = BigDecimal.ZERO,
    val timestamp: Long,         // 거래소 발생 타임스탬프 (epoch ms)
)
