package com.allfolio.kafka

import com.allfolio.kafka.idempotency.KafkaIdempotencyService
import com.allfolio.metrics.BrokerMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Component

/**
 * outbox.trade 토픽 Consumer — INSERT 기반 멱등성
 *
 * 멱등성 흐름:
 * 1. tryMarkProcessed(eventId) → INSERT (REQUIRES_NEW TX, 즉시 커밋)
 *    ├─ DataIntegrityViolationException → 중복 → ACK + skip
 *    └─ 성공 → 신규 이벤트
 * 2. processEvent() 실행
 *    ├─ 성공 → ACK
 *    └─ 실패 → unmarkProcessed() (보상) → DON'T ACK → Kafka retry
 *
 * 왜 SELECT 없이 INSERT만 쓰는가:
 * - SELECT-then-INSERT: race condition 존재 (두 인스턴스 동시 처리 가능)
 * - INSERT: PK 충돌로 원자적 중복 감지 → race condition 불가능
 *
 * 처리 실패 → Kafka retry 보장:
 * - 실패 시 unmarkProcessed() → DB에서 마커 삭제 → retry 시 INSERT 성공 → 재처리
 * - ACK 안 함 → max.poll.records 이내 재시도
 *
 * 성능:
 * - INSERT 1회 (O(1)), SELECT 0회
 * - concurrency=6: 파티션 수와 동일 → 최대 병렬성
 * - kafka.consumer.process.latency 타이머로 p95/p99 측정
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"], matchIfMissing = false)
class OutboxKafkaConsumer(
    private val idempotencyService: KafkaIdempotencyService,
    private val metrics: BrokerMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics           = [KafkaTopicConfig.OUTBOX_TRADE],
        groupId          = "allfolio-outbox-consumer",
        concurrency      = "6",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun consume(
        @Payload message: String,
        @Header(KafkaHeaders.RECEIVED_PARTITION) partition: Int,
        @Header(KafkaHeaders.OFFSET) offset: Long,
        ack: Acknowledgment,
    ) {
        val event = runCatching {
            OutboxKafkaPublisher.MAPPER.readValue(message, OutboxKafkaPublisher.OutboxTradeMessage::class.java)
        }.getOrElse { e ->
            log.error("[OutboxConsumer] deserialize failed partition={} offset={}: {}", partition, offset, e.message)
            metrics.kafkaConsumerDeserializeFailed()
            ack.acknowledge()  // 역직렬화 실패 = 재시도 불가 → ACK 후 skip
            return
        }

        val eventId = event.outboxEventId.toString()

        // ── Step 1: INSERT 기반 멱등성 ──────────────────────────
        if (!idempotencyService.tryMarkProcessed(eventId)) {
            log.debug("[OutboxConsumer] duplicate skip outboxId={} partition={} offset={}",
                eventId, partition, offset)
            metrics.kafkaConsumerDuplicate()
            ack.acknowledge()
            return
        }

        // ── Step 2: 실제 처리 ────────────────────────────────────
        try {
            metrics.recordKafkaConsumerLatency {
                processEvent(event)
            }
            metrics.outboxKafkaConsumed()
            log.info("[OutboxConsumer] processed outboxId={} portfolio={} partition={} offset={}",
                eventId, event.portfolioId, partition, offset)
            ack.acknowledge()
        } catch (e: Exception) {
            // 처리 실패 → 보상: 마커 제거 → Kafka retry가 재처리 가능하게
            log.error("[OutboxConsumer] processing failed outboxId={} partition={} offset={}",
                eventId, partition, offset, e)
            metrics.outboxKafkaConsumeFailed()
            idempotencyService.unmarkProcessed(eventId)  // 보상 액션
            // DON'T ACK → Kafka retry
        }
    }

    /**
     * 이벤트 처리 확장 지점
     * - 현재: 로깅 (구조적 no-op)
     * - 확장: 캐시 warming, 외부 webhook, BI 파이프라인, 알림 발송
     */
    private fun processEvent(event: OutboxKafkaPublisher.OutboxTradeMessage) {
        log.debug("[OutboxConsumer] processEvent outboxId={} portfolio={} asset={} tradeDate={}",
            event.outboxEventId, event.portfolioId, event.assetId, event.tradeDate)
        // Extension point — 실제 처리 로직 추가
    }
}
