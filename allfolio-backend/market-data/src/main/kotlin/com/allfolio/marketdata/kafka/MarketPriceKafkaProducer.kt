package com.allfolio.marketdata.kafka

import com.allfolio.marketdata.adapter.InternalPriceEvent
import com.allfolio.marketdata.metrics.MarketMetrics
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * 실시간 시세 → Kafka market.prices 발행
 *
 * 흐름: WsAdapter → InternalPriceEvent → (이 컴포넌트) → Kafka
 *
 * 성능:
 *   send()는 비동기 (RecordAccumulator 적재 → linger.ms 후 배치 전송)
 *   linger.ms=5: 5ms 내 도착한 메시지 묶음 전송 → 처리량 향상
 *
 * 장애:
 *   Kafka 다운 → send() 실패 → 로그 경고 (틱 손실 허용)
 *   틱 데이터는 DB BatchWriter에도 저장되므로 Kafka 손실은 PnL 지연만 유발
 */
@Component
class MarketPriceKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val metrics: MarketMetrics,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("wsEventExecutor")
    @EventListener
    fun onPriceUpdate(event: InternalPriceEvent) {
        val kafkaEvent = MarketPriceEvent(
            exchange  = event.exchange,
            symbol    = event.symbol,
            assetId   = event.assetId,
            price     = event.price,
            volume    = event.volume,
            timestamp = event.timestamp,
        )

        val key   = "${event.exchange}:${event.symbol}"
        val value = runCatching { objectMapper.writeValueAsString(kafkaEvent) }
            .getOrElse { return }

        kafkaTemplate.send(TopicConstants.MARKET_PRICES, key, value)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("[MarketProducer] send failed symbol={}: {}", event.symbol, ex.message)
                    metrics.kafkaSendFailed(event.exchange)
                } else {
                    metrics.kafkaSendSuccess(event.exchange)
                }
            }
    }
}
