package com.allfolio.kafka

import com.allfolio.market.PriceUpdateEvent
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * market.prices 토픽 소비 → PriceUpdateEvent publish
 *
 * market-data 서비스가 WsAdapter → Kafka에 발행한 시세 데이터를 수신하여
 * 기존 PnlCalculationService의 @EventListener 흐름으로 연결.
 *
 * group.id=allfolio-pnl: PnL 계산 전용 컨슈머 그룹
 * concurrency=6: market.prices 12 파티션의 절반 — CPU 코어 여유에 맞춤
 */
@Component
@ConditionalOnProperty(name = ["kafka.enabled"], havingValue = "true", matchIfMissing = false)
class MarketPriceKafkaConsumer(
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics            = ["market.prices"],
        groupId           = "allfolio-pnl",
        concurrency       = "6",
        containerFactory  = "kafkaListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        runCatching {
            val dto = objectMapper.readValue(record.value(), MarketPriceDto::class.java)
            val event = PriceUpdateEvent(
                exchange  = dto.exchange,
                symbol    = dto.symbol,
                assetId   = UUID.fromString(dto.assetId),
                price     = dto.price,
                timestamp = dto.timestamp,
            )
            eventPublisher.publishEvent(event)
        }.onFailure { e ->
            log.warn("[MarketConsumer] parse error key={}: {}", record.key(), e.message)
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MarketPriceDto(
        val exchange:  String     = "",
        val symbol:    String     = "",
        val assetId:   String     = "",
        val price:     BigDecimal = BigDecimal.ZERO,
        val volume:    BigDecimal = BigDecimal.ZERO,
        val timestamp: Long       = 0L,
    )
}
