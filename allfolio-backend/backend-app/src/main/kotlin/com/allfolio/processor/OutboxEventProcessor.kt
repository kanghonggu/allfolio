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

/** DEAD 수동 재처리 결과 (AF-7). */
data class OutboxReprocessResult(val processed: Int, val failed: Int, val skipped: Int)

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

        handleGroups(events) { groupEvents, e ->
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
    }

    /**
     * DEAD 이벤트 수동 재처리 (어드민, AF-7) — 기존 폴러 경로(같은 트리거·같은 멱등성) 재사용.
     * 1회성 시도: 성공 → PROCESSED, 실패 → DEAD 유지 + errorMessage 갱신. retryCount는 보존.
     * skipped = 요청 id 중 DEAD 아님/미존재/타 인스턴스 락.
     */
    @Transactional
    fun reprocessDead(ids: List<UUID>): OutboxReprocessResult {
        val events = outboxRepository.findDeadByIdsForUpdate(ids)
        val skipped = ids.size - events.size
        if (events.isEmpty()) return OutboxReprocessResult(0, 0, skipped)

        log.info("[Outbox-Reprocess] {} dead events (requested {})", events.size, ids.size)

        var failed = 0
        handleGroups(events) { groupEvents, e ->
            failed += groupEvents.size
            groupEvents.forEach { event ->
                event.errorMessage = "manual reprocess failed: ${e.message?.take(450)}"
                // status DEAD·retryCount 보존 — 1회성 수동 시도
            }
        }
        val processed = events.count { it.status == OutboxStatus.PROCESSED }
        return OutboxReprocessResult(processed, failed, skipped)
    }

    /** (tenant, portfolio, date) 그룹별 스냅샷 트리거 — 실패 시 상태 전이는 onGroupFailure 정책에 위임. */
    private fun handleGroups(
        events: List<OutboxEventEntity>,
        onGroupFailure: (List<OutboxEventEntity>, Exception) -> Unit,
    ) {
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
                onGroupFailure(groupEvents, e)
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
