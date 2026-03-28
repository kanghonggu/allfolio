package com.allfolio.processor

import com.allfolio.metrics.BrokerMetrics
import com.allfolio.service.SnapshotTriggerService
import com.allfolio.trade.infrastructure.outbox.OutboxEventEntity
import com.allfolio.trade.infrastructure.outbox.OutboxEventPublisher
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Outbox 이벤트 폴링 프로세서 (안전망 — Safety Net)
 *
 * 1차 처리: TradeEventListener (AFTER_COMMIT, 실시간 ~200ms)
 * 2차 처리: 이 프로세서 (30초 polling, 장애 복구용)
 *
 * 실행 조건:
 * - TradeEventListener 성공 → outbox_event PROCESSED → 이 프로세서 SKIP
 * - TradeEventListener 실패 → outbox_event FAILED   → 이 프로세서 재처리
 * - TradeEventListener 미실행(서버 재시작 등) → outbox_event PENDING → 이 프로세서 처리
 *
 * 안전장치:
 * - SELECT FOR UPDATE SKIP LOCKED: 다중 인스턴스 중복 처리 방지 (논블로킹)
 * - @Transactional: 상태 전이 원자성 보장
 * - MAX_RETRIES: retryCount ≥ MAX → DEAD 전이 (무한 루프 차단)
 *
 * Kafka 전환 시:
 * - outbox_event → Debezium CDC → Kafka → Consumer 로 대체
 */
@Component
class OutboxEventProcessor(
    private val outboxRepository: OutboxRepository,
    private val snapshotTriggerService: SnapshotTriggerService,
    private val metrics: BrokerMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 30초 주기 — TradeEventListener 이후 PENDING/FAILED 남은 이벤트만 처리 */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    fun process() {
        // FOR UPDATE SKIP LOCKED: 다른 인스턴스가 처리 중인 row 건너뜀
        val events = outboxRepository.findRetryableForUpdate(MAX_RETRIES)
        if (events.isEmpty()) return

        log.info("[Outbox-Polling] {} retryable events", events.size)

        val groups: Map<Triple<UUID, UUID, LocalDate>, List<OutboxEventEntity>> = events
            .groupBy { event ->
                val payload = OutboxEventPublisher.MAPPER.readValue(
                    event.payload,
                    OutboxEventPublisher.TradeRecordedPayload::class.java,
                )
                Triple(payload.tenantId, payload.portfolioId, payload.tradeDate)
            }

        groups.forEach { (key, groupEvents) ->
            val (tenantId, portfolioId, tradeDate) = key
            try {
                metrics.recordOutboxLatency {
                    snapshotTriggerService.trigger(
                        tenantId      = tenantId,
                        portfolioId   = portfolioId,
                        tradeDate     = tradeDate,
                        currentPrices = extractLatestPrices(groupEvents),
                    )
                }
                groupEvents.forEach { event ->
                    event.status      = OutboxStatus.PROCESSED
                    event.processedAt = LocalDateTime.now()
                }
                metrics.outboxProcessed(groupEvents.size)
                log.info("[Outbox-Polling] PROCESSED tenant={} portfolio={} date={} count={}",
                    tenantId, portfolioId, tradeDate, groupEvents.size)
            } catch (e: Exception) {
                log.error("[Outbox-Polling] FAILED tenant={} portfolio={} date={}", tenantId, portfolioId, tradeDate, e)
                groupEvents.forEach { event ->
                    val newRetryCount = event.retryCount + 1
                    if (newRetryCount >= MAX_RETRIES) {
                        event.status       = OutboxStatus.DEAD
                        event.errorMessage = "MAX_RETRIES exceeded: ${e.message?.take(400)}"
                        metrics.outboxDead()
                        log.warn("[Outbox-Polling] DEAD outboxEventId={} retries={}", event.id, newRetryCount)
                    } else {
                        event.status       = OutboxStatus.FAILED
                        event.errorMessage = e.message?.take(500)
                        event.retryCount   = newRetryCount
                        metrics.outboxFailed()
                    }
                }
            }
            outboxRepository.saveAll(groupEvents)
        }
    }

    private fun extractLatestPrices(
        events: List<OutboxEventEntity>,
    ): Map<UUID, java.math.BigDecimal> = events
        .map { OutboxEventPublisher.MAPPER.readValue(it.payload, OutboxEventPublisher.TradeRecordedPayload::class.java) }
        .groupBy { it.assetId }
        .mapValues { (_, payloads) -> payloads.last().price }

    companion object {
        /** 최대 재시도 횟수 — 초과 시 DEAD 전이 */
        const val MAX_RETRIES = 5
    }
}
