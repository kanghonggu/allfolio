package com.allfolio.processor

import com.allfolio.metrics.BrokerMetrics
import com.allfolio.service.SnapshotTriggerService
import com.allfolio.trade.domain.TradeRecordedEvent
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.LocalDateTime

/**
 * Outbox 패턴 1차 처리 경로 (실시간 ~200ms)
 *
 * 흐름:
 * RecordTradeUseCase TX 커밋
 *   → applicationEventPublisher.publishEvent(TradeRecordedEvent)
 *   → AFTER_COMMIT 발화
 *   → @Async 스레드풀에서 실행 (원본 TX 스레드 즉시 반환)
 *   → snapshotTriggerService.trigger()
 *   → outbox_event PROCESSED 전이 (새 독립 TX)
 *
 * 실패 시:
 *   → outbox_event FAILED 전이 (retryCount 증가)
 *   → OutboxEventProcessor(30초 폴링)가 안전망으로 재처리
 *
 * 성능 원칙:
 * - @Async: Trade write path 스레드 즉시 반환, snapshot은 별도 스레드풀
 * - @Transactional(REQUIRES_NEW): RecordTrade TX와 완전 독립 — 스냅샷 실패가 Trade 롤백 유발 안 함
 */
@Component
class TradeEventListener(
    private val snapshotTriggerService: SnapshotTriggerService,
    private val outboxRepository: OutboxRepository,
    private val metrics: BrokerMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun onTradeRecorded(event: TradeRecordedEvent) {
        log.debug("[TradeEventListener] processing outboxEventId={} portfolio={}", event.outboxEventId, event.portfolioId)

        try {
            snapshotTriggerService.trigger(
                tenantId      = event.tenantId,
                portfolioId   = event.portfolioId,
                tradeDate     = event.tradeDate,
                currentPrices = mapOf(event.assetId to event.price),
            )
            markProcessed(event.outboxEventId)
            metrics.outboxProcessed()
            log.info("[TradeEventListener] PROCESSED outboxEventId={} portfolio={}", event.outboxEventId, event.portfolioId)
        } catch (e: Exception) {
            log.error("[TradeEventListener] FAILED outboxEventId={} portfolio={}", event.outboxEventId, event.portfolioId, e)
            markFailed(event.outboxEventId, e.message)
            metrics.outboxFailed()
            // 예외를 삼킴 — AFTER_COMMIT이므로 이미 커밋된 TX에 영향 없음
            // OutboxEventProcessor가 FAILED 이벤트를 재처리
        }
    }

    private fun markProcessed(outboxEventId: java.util.UUID) {
        outboxRepository.findById(outboxEventId).ifPresent { entity ->
            entity.status      = OutboxStatus.PROCESSED
            entity.processedAt = LocalDateTime.now()
            outboxRepository.save(entity)
        }
    }

    private fun markFailed(outboxEventId: java.util.UUID, errorMessage: String?) {
        outboxRepository.findById(outboxEventId).ifPresent { entity ->
            entity.status       = OutboxStatus.FAILED
            entity.errorMessage = errorMessage?.take(500)
            entity.retryCount   = entity.retryCount + 1
            outboxRepository.save(entity)
        }
    }
}
