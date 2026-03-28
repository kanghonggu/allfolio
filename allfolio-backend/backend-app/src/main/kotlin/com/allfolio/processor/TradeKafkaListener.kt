package com.allfolio.processor

import com.allfolio.kafka.OutboxKafkaPublisher
import com.allfolio.trade.domain.TradeRecordedEvent
import com.allfolio.trade.infrastructure.outbox.OutboxEventPublisher
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.LocalDateTime

/**
 * Outbox → Kafka 전파 리스너 (TradeEventListener와 완전 독립)
 *
 * 흐름:
 * RecordTradeUseCase TX 커밋
 *   → AFTER_COMMIT 발화
 *   → @Async 스레드풀에서 실행 (TradeEventListener와 병렬 실행)
 *   → OutboxKafkaPublisher.publish() (비동기 send)
 *   → 성공: outbox_event → PROCESSED_KAFKA
 *   → 실패: 무시 — OutboxEventProcessor가 DB 경로로 재처리
 *
 * 절대 조건:
 * - Kafka 장애 시 예외 삼킴 → Trade write path, Snapshot에 영향 없음
 * - ConditionalOnBean: Kafka 미설정(OutboxKafkaPublisher Bean 없음) 시 동작 없음
 *
 * 성능:
 * - @Async: Trade write path 스레드 즉시 반환
 * - kafkaTemplate.send(): 내부 RecordAccumulator 적재, 호출 즉시 반환 O(1)
 */
@Component
class TradeKafkaListener(
    private val outboxKafkaPublisher: OutboxKafkaPublisher?,  // Kafka 미설정 시 null
    private val outboxRepository: OutboxRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onTradeRecorded(event: TradeRecordedEvent) {
        // Kafka 미설정 시 skip (OutboxKafkaPublisher Bean 없음)
        val publisher = outboxKafkaPublisher ?: return

        runCatching {
            val message = buildMessage(event)
            publisher.publish(message)
            // Kafka 발행 성공 → PROCESSED_KAFKA 전이 (별도 TX)
            markProcessedKafka(event)
        }.onFailure { e ->
            // Kafka 실패는 무시 — DB Outbox가 최종 보장
            log.warn("[TradeKafkaListener] Kafka publish failed outboxId={} portfolio={}: {}",
                event.outboxEventId, event.portfolioId, e.message)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markProcessedKafka(event: TradeRecordedEvent) {
        outboxRepository.findById(event.outboxEventId).ifPresent { entity ->
            // PROCESSED보다 높은 상태이므로 이미 DEAD/FAILED인 경우 덮어쓰지 않음
            if (entity.status == OutboxStatus.PROCESSED || entity.status == OutboxStatus.PENDING) {
                entity.status      = OutboxStatus.PROCESSED_KAFKA
                entity.processedAt = LocalDateTime.now()
                outboxRepository.save(entity)
            }
        }
    }

    private fun buildMessage(event: TradeRecordedEvent): OutboxKafkaPublisher.OutboxTradeMessage {
        // outbox_event의 payload를 직접 파싱해서 tradeId 확보
        val entity  = outboxRepository.findById(event.outboxEventId).orElse(null)
        val payload = entity?.let {
            runCatching {
                OutboxEventPublisher.MAPPER.readValue(it.payload, OutboxEventPublisher.TradeRecordedPayload::class.java)
            }.getOrNull()
        }

        return if (payload != null) {
            outboxKafkaPublisher!!.buildMessage(event.outboxEventId, payload)
        } else {
            // fallback: TradeRecordedEvent 필드만으로 구성 (tradeId = outboxEventId 임시 사용)
            OutboxKafkaPublisher.OutboxTradeMessage(
                outboxEventId = event.outboxEventId,
                eventType     = "TRADE_RECORDED",
                tenantId      = event.tenantId,
                portfolioId   = event.portfolioId,
                assetId       = event.assetId,
                tradeId       = event.outboxEventId,
                price         = event.price,
                tradeDate     = event.tradeDate,
                publishedAt   = java.time.LocalDateTime.now(),
            )
        }
    }
}
