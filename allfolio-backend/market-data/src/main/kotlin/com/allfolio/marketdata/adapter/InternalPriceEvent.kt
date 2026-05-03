package com.allfolio.marketdata.adapter

import java.math.BigDecimal

/**
 * market-data 서비스 내부 Spring ApplicationEvent
 *
 * WsAdapter → ApplicationEventPublisher.publishEvent(InternalPriceEvent)
 *   → @Async @EventListener MarketPriceKafkaProducer  (Kafka 발행)
 *   → @Async @EventListener MarketPriceBatchWriter     (DB 배치 저장)
 */
data class InternalPriceEvent(
    val exchange:  String,
    val symbol:    String,
    val assetId:   String,   // UUID string
    val price:     BigDecimal,
    val volume:    BigDecimal = BigDecimal.ZERO,
    val timestamp: Long,
)
