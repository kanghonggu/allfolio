package com.allfolio.processor

import com.allfolio.dlq.DlqService
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.service.SnapshotTriggerService
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.trade.infrastructure.outbox.OutboxEventEntity
import com.allfolio.trade.infrastructure.outbox.OutboxEventPublisher
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class OutboxEventProcessorReprocessTest {

    private val tenantId = UUID.randomUUID()
    private val portfolioId = UUID.randomUUID()
    private val tradeDate = LocalDate.of(2026, 7, 1)

    private fun deadEvent(retryCount: Int = OutboxEventProcessor.MAX_RETRIES): OutboxEventEntity {
        val payload = OutboxEventPublisher.MAPPER.writeValueAsString(
            OutboxEventPublisher.TradeRecordedPayload(
                tradeId = UUID.randomUUID(),
                tenantId = tenantId,
                portfolioId = portfolioId,
                assetId = UUID.randomUUID(),
                tradeDate = tradeDate,
                price = BigDecimal("100"),
            )
        )
        return OutboxEventEntity(
            id = UUID.randomUUID(),
            aggregateType = "TRADE",
            aggregateId = UUID.randomUUID(),
            eventType = "TRADE_RECORDED",
            payload = payload,
            status = OutboxStatus.DEAD,
            createdAt = LocalDateTime.now(),
            retryCount = retryCount,
            errorMessage = "MAX_RETRIES exceeded: boom",
        )
    }

    private fun processor(
        repo: OutboxRepository,
        trigger: SnapshotTriggerService,
    ) = OutboxEventProcessor(
        repo, trigger,
        // 실제 BrokerMetrics(람다 실행 필요) + mock 의존성 — @PostConstruct는 Spring 밖에서 미실행
        BrokerMetrics(SimpleMeterRegistry(), mock(DlqService::class.java), mock(OutboxRepository::class.java)),
    )

    // Kotlin non-null 파라미터에 Mockito matcher를 쓰기 위한 null-safe 래퍼 (기존 테스트 컨벤션).
    private fun anyUuid(): UUID = any(UUID::class.java) ?: UUID.randomUUID()
    private fun anyDate(): LocalDate = any(LocalDate::class.java) ?: LocalDate.MIN
    private fun anyPrices(): Map<UUID, BigDecimal> = anyMap<UUID, BigDecimal>() ?: emptyMap()

    /** trigger 성공 스텁 — recordOutboxLatency의 `!!` 때문에 non-null 반환 필수. */
    private fun stubTriggerSuccess(trigger: SnapshotTriggerService) {
        doReturn(mock(PerformanceDailyEntity::class.java)).`when`(trigger)
            .trigger(anyUuid(), anyUuid(), anyDate(), anyPrices())
    }

    private fun stubTriggerFailure(trigger: SnapshotTriggerService, message: String) {
        doThrow(RuntimeException(message)).`when`(trigger)
            .trigger(anyUuid(), anyUuid(), anyDate(), anyPrices())
    }

    @Test
    fun `성공 시 PROCESSED 전이·retryCount 보존`() {
        val e1 = deadEvent(); val e2 = deadEvent()
        val repo = mock(OutboxRepository::class.java)
        val trigger = mock(SnapshotTriggerService::class.java)
        stubTriggerSuccess(trigger)
        `when`(repo.findDeadByIdsForUpdate(listOf(e1.id, e2.id))).thenReturn(listOf(e1, e2))

        val result = processor(repo, trigger).reprocessDead(listOf(e1.id, e2.id))

        assertEquals(2, result.processed)
        assertEquals(0, result.failed)
        assertEquals(0, result.skipped)
        assertEquals(OutboxStatus.PROCESSED, e1.status)
        assertEquals(OutboxStatus.PROCESSED, e2.status)
        assertEquals(OutboxEventProcessor.MAX_RETRIES, e1.retryCount)
        assertNotNull(e1.processedAt)
    }

    @Test
    fun `실패 시 DEAD 유지·errorMessage 갱신·retryCount 보존`() {
        val e1 = deadEvent()
        val repo = mock(OutboxRepository::class.java)
        val trigger = mock(SnapshotTriggerService::class.java)
        `when`(repo.findDeadByIdsForUpdate(listOf(e1.id))).thenReturn(listOf(e1))
        stubTriggerFailure(trigger, "snapshot down")

        val result = processor(repo, trigger).reprocessDead(listOf(e1.id))

        assertEquals(0, result.processed)
        assertEquals(1, result.failed)
        assertEquals(OutboxStatus.DEAD, e1.status)
        assertTrue(e1.errorMessage!!.contains("manual reprocess failed"))
        assertEquals(OutboxEventProcessor.MAX_RETRIES, e1.retryCount)
    }

    @Test
    fun `DEAD 아니거나 없는 id는 skipped로 집계`() {
        val e1 = deadEvent()
        val notDeadId = UUID.randomUUID()
        val repo = mock(OutboxRepository::class.java)
        val trigger = mock(SnapshotTriggerService::class.java)
        stubTriggerSuccess(trigger)
        `when`(repo.findDeadByIdsForUpdate(listOf(e1.id, notDeadId))).thenReturn(listOf(e1))

        val result = processor(repo, trigger).reprocessDead(listOf(e1.id, notDeadId))

        assertEquals(1, result.processed)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `기존 폴링 경로 회귀 - 실패 시 retry 증가·MAX 도달 시 DEAD`() {
        val pending = deadEvent(retryCount = 0).apply { status = OutboxStatus.PENDING }
        val nearDead = deadEvent(retryCount = OutboxEventProcessor.MAX_RETRIES - 1)
            .apply { status = OutboxStatus.FAILED }
        val repo = mock(OutboxRepository::class.java)
        val trigger = mock(SnapshotTriggerService::class.java)
        `when`(repo.findRetryableForUpdate(anyInt())).thenReturn(listOf(pending, nearDead))
        stubTriggerFailure(trigger, "still down")

        processor(repo, trigger).process()

        assertEquals(OutboxStatus.FAILED, pending.status)
        assertEquals(1, pending.retryCount)
        assertEquals(OutboxStatus.DEAD, nearDead.status)
    }
}
