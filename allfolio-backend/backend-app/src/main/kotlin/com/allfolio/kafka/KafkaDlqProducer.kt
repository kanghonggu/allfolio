package com.allfolio.kafka

import com.allfolio.dlq.FailedTradeEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * Kafka DLQ Producer
 *
 * 설계 원칙:
 * - KafkaTemplate.send(): 비동기 (ListenableFuture 반환) — 절대 blocking 없음
 * - 실패 시 로그만 남김 (Redis DLQ가 primary fallback)
 * - ConditionalOnProperty: Kafka 미설정 시 Bean 미생성 → DlqService가 Redis만 사용
 *
 * 성능 영향:
 * - send() → 내부 RecordAccumulator(batch buffer) 에 적재 → Kafka sender 스레드가 비동기 전송
 * - 호출 스레드 블로킹 없음, O(1) 메모리 적재
 *
 * topic 매핑:
 *   TRADE_COMMAND → dlq.trade
 *   FETCH_PARAMS  → dlq.fetch
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"], matchIfMissing = false)
class KafkaDlqProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(event: FailedTradeEvent) {
        val topic = when (event.payloadType) {
            FailedTradeEvent.TYPE_TRADE_COMMAND -> KafkaTopicConfig.DLQ_TRADE
            FailedTradeEvent.TYPE_FETCH_PARAMS  -> KafkaTopicConfig.DLQ_FETCH
            else                               -> KafkaTopicConfig.DLQ_TRADE
        }
        sendToTopic(topic, event)
    }

    fun sendDead(event: FailedTradeEvent) = sendToTopic(KafkaTopicConfig.DLQ_DEAD, event)

    private fun sendToTopic(topic: String, event: FailedTradeEvent) {
        runCatching {
            val json   = objectMapper.writeValueAsString(event)
            val future = kafkaTemplate.send(topic, event.brokerType, json)  // key=brokerType (파티션 분산)
            future.whenComplete { result, ex ->
                if (ex != null) {
                    log.warn("[KafkaDLQ] send failed topic={} broker={} id={}: {}", topic, event.brokerType, event.id, ex.message)
                } else {
                    log.debug("[KafkaDLQ] sent topic={} partition={} offset={}",
                        topic, result.recordMetadata.partition(), result.recordMetadata.offset())
                }
            }
        }.onFailure { e ->
            log.error("[KafkaDLQ] producer error topic={} id={}", topic, event.id, e)
        }
    }
}
