package com.allfolio.kafka

import com.allfolio.metrics.BrokerMetrics
import com.allfolio.trade.infrastructure.outbox.OutboxEventPublisher
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Outbox 이벤트 Kafka 전파 Producer
 *
 * 설계 원칙:
 * - DB Outbox는 Source of Truth, Kafka는 전파 채널 역할
 * - 실패 시: 예외 미전파 + 로그만 → DB Outbox가 최종 보장
 * - ConditionalOnProperty: Kafka 미설정 시 Bean 미생성 → 시스템 정상 동작 유지
 * - 비동기 send(): RecordAccumulator에 적재 → 호출 스레드 블로킹 없음 O(1)
 *
 * 토픽: outbox.trade
 * 파티션 키: portfolioId → 동일 포트폴리오 이벤트 순서 보장
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"], matchIfMissing = false)
class OutboxKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val metrics: BrokerMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * outbox.trade 토픽 발행
     * key = portfolioId: 동일 포트폴리오 파티션 고정 → 순서 보장
     */
    fun publish(message: OutboxTradeMessage) {
        runCatching {
            val json   = MAPPER.writeValueAsString(message)
            val future = kafkaTemplate.send(KafkaTopicConfig.OUTBOX_TRADE, message.portfolioId.toString(), json)
            future.whenComplete { result, ex ->
                if (ex != null) {
                    log.warn("[OutboxKafka] publish failed outboxId={} portfolio={}: {}",
                        message.outboxEventId, message.portfolioId, ex.message)
                    metrics.kafkaPublishFailed()
                } else {
                    log.debug("[OutboxKafka] published partition={} offset={} outboxId={}",
                        result.recordMetadata.partition(), result.recordMetadata.offset(), message.outboxEventId)
                    metrics.kafkaPublishSuccess()
                }
            }
        }.onFailure { e ->
            // 직렬화 실패 등 send() 호출 전 예외 — 여기서도 삼킴
            log.error("[OutboxKafka] publisher error outboxId={}", message.outboxEventId, e)
            metrics.kafkaPublishFailed()
        }
    }

    /**
     * TradeRecordedPayload로부터 Kafka 메시지 구성
     * Outbox EventEntity를 직접 노출하지 않도록 변환
     */
    fun buildMessage(
        outboxEventId: UUID,
        payload: OutboxEventPublisher.TradeRecordedPayload,
    ): OutboxTradeMessage = OutboxTradeMessage(
        outboxEventId = outboxEventId,
        eventType     = OutboxEventPublisher.EVENT_TYPE,
        tenantId      = payload.tenantId,
        portfolioId   = payload.portfolioId,
        assetId       = payload.assetId,
        tradeId       = payload.tradeId,
        price         = payload.price,
        tradeDate     = payload.tradeDate,
        publishedAt   = LocalDateTime.now(),
    )

    /** outbox.trade Kafka 메시지 스키마 */
    data class OutboxTradeMessage(
        val outboxEventId: UUID,
        val eventType: String,
        val tenantId: UUID,
        val portfolioId: UUID,
        val assetId: UUID,
        val tradeId: UUID,
        val price: BigDecimal,
        val tradeDate: LocalDate,
        val publishedAt: LocalDateTime,
    )

    companion object {
        val MAPPER = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
