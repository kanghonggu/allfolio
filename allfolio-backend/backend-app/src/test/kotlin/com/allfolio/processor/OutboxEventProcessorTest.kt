package com.allfolio.processor

import com.allfolio.dlq.DlqService
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.service.SnapshotTriggerService
import com.allfolio.trade.infrastructure.outbox.OutboxEventEntity
import com.allfolio.trade.infrastructure.outbox.OutboxEventPublisher
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class OutboxEventProcessorTest {

    private fun pendingEvent(
        tenantId: UUID = UUID.randomUUID(),
        portfolioId: UUID = UUID.randomUUID(),
        tradeDate: LocalDate = LocalDate.of(2026, 7, 30),
    ): OutboxEventEntity {
        val payload = OutboxEventPublisher.MAPPER.writeValueAsString(
            OutboxEventPublisher.TradeRecordedPayload(
                tradeId     = UUID.randomUUID(),
                tenantId    = tenantId,
                portfolioId = portfolioId,
                assetId     = UUID.randomUUID(),
                price       = BigDecimal("100.00"),
                tradeDate   = tradeDate,
            )
        )
        return OutboxEventEntity(
            id            = UUID.randomUUID(),
            aggregateType = "TRADE",
            aggregateId   = UUID.randomUUID(),
            eventType     = OutboxEventPublisher.EVENT_TYPE,
            payload       = payload,
            status        = OutboxStatus.PENDING,
            createdAt     = LocalDateTime.now(),
        )
    }

    private fun processor(
        repo: OutboxRepository,
        trigger: SnapshotTriggerService,
    ): OutboxEventProcessor {
        // 실제 BrokerMetrics를 사용해 recordOutboxLatency의 null 처리까지 검증한다
        val metrics = BrokerMetrics(SimpleMeterRegistry(), Mockito.mock(DlqService::class.java), repo)
        return OutboxEventProcessor(repo, trigger, metrics)
    }

    @Test
    fun `trigger가 null을 반환해도 (스냅샷 스킵) 이벤트는 PROCESSED로 전이된다`() {
        val event = pendingEvent()
        val repo = Mockito.mock(OutboxRepository::class.java)
        Mockito.`when`(repo.findRetryableForUpdate(OutboxEventProcessor.MAX_RETRIES))
            .thenReturn(listOf(event))

        // 스텁 없는 mock — trigger(...)는 null 반환 (no trades/prices 케이스)
        val trigger = Mockito.mock(SnapshotTriggerService::class.java)

        processor(repo, trigger).process()

        assertEquals(OutboxStatus.PROCESSED, event.status)
        assertNotNull(event.processedAt)
        assertNull(event.errorMessage)
        assertEquals(0, event.retryCount)
    }
}
