package com.allfolio.listener

import com.allfolio.service.SnapshotTriggerService
import com.allfolio.trade.domain.TradeRecordedEvent
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.LocalDateTime

/**
 * Trade 커밋 후 실시간 Snapshot 생성
 *
 * 경로: Trade @Transactional 커밋 → AFTER_COMMIT 이벤트 → @Async 스레드
 *
 * 설계 원칙:
 * - @Async: HTTP 응답을 블로킹하지 않음 (비동기 처리)
 * - AFTER_COMMIT: 원본 트랜잭션 커밋 완료 후에만 실행
 * - 실패 시 outbox_event PENDING 유지 → Polling Processor가 안전망
 * - SnapshotTriggerService 내부에서 generate() @Transactional 별도 실행
 * - markProcessed: outboxRepository.save() 는 자체 @Transactional → self-invocation 불필요
 */
@Component
class TradeEventListener(
    private val snapshotTriggerService: SnapshotTriggerService,
    private val outboxRepository: OutboxRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handle(event: TradeRecordedEvent) {
        val start = System.currentTimeMillis()
        try {
            snapshotTriggerService.trigger(
                tenantId      = event.tenantId,
                portfolioId   = event.portfolioId,
                tradeDate     = event.tradeDate,
                currentPrices = mapOf(event.assetId to event.price),
            )
            // Spring Data save() 는 자체 트랜잭션 — self-invocation 없이 직접 호출
            outboxRepository.findById(event.outboxEventId).ifPresent {
                it.status      = OutboxStatus.PROCESSED
                it.processedAt = LocalDateTime.now()
                outboxRepository.save(it)
            }
            log.info("[Event] AFTER_COMMIT done in {}ms portfolio={} date={}",
                System.currentTimeMillis() - start, event.portfolioId, event.tradeDate)
        } catch (e: Exception) {
            log.error("[Event] AFTER_COMMIT failed portfolio={} date={} — Polling fallback active",
                event.portfolioId, event.tradeDate, e)
            // outbox_event PENDING 유지 → OutboxEventProcessor 안전망이 재처리
        }
    }
}
