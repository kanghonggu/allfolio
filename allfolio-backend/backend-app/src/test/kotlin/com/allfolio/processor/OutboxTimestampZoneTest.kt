package com.allfolio.processor

import com.allfolio.dlq.DlqService
import com.allfolio.kafka.OutboxKafkaPublisher
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.service.SnapshotTriggerService
import com.allfolio.trade.domain.TradeRecordedEvent
import com.allfolio.trade.infrastructure.outbox.OutboxEventEntity
import com.allfolio.trade.infrastructure.outbox.OutboxEventPublisher
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.TimeZone
import java.util.UUID

/**
 * `outbox_event.created_at` / `processed_at`은 존 없는 `TIMESTAMP`다. 그래서 여기 찍히는 **벽시계가
 * 곧 DB에 앉는 값**이고, 읽는 쪽([com.allfolio.api.admin.OpsAdminController])은 그 값을 UTC로
 * 전제하고 오프셋을 단다.
 *
 * 존을 안 쓰면 그 전제가 **호스트 TZ에 기댄 우연**이 된다 — 운영(Render) 컨테이너가 UTC라 맞았을
 * 뿐이다. KST 호스트에서 돌리면 9시간 미래가 UTC인 척 저장되고, 화면은 그걸 다시 9시간 앞으로
 * 밀어 18시간 미래를 보여준다.
 *
 * 찍는 자리가 네 곳이라 네 곳 모두 못 박는다 — 한 곳만 남아도 그 경로로 들어온 행만 조용히 틀린다.
 */
class OutboxTimestampZoneTest {

    /** 호스트가 UTC가 **아닌** 조건. 운영이 UTC라 이 테스트는 우연에 기대지 않기 위해 존재한다. */
    private fun <T> inKstHost(block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun assertStampedInUtc(stamped: LocalDateTime?) {
        assertThat(stamped).isNotNull
        assertThat(Duration.between(stamped!!.toInstant(ZoneOffset.UTC), Instant.now()).abs())
            .describedAs("호스트 벽시계를 그대로 적으면 KST 호스트에서 9시간 미래가 UTC인 척 저장된다")
            .isLessThan(Duration.ofMinutes(1))
    }

    private fun entity(status: OutboxStatus = OutboxStatus.PENDING, payload: String = "{}") = OutboxEventEntity(
        id            = UUID.randomUUID(),
        aggregateType = "TRADE",
        aggregateId   = UUID.randomUUID(),
        eventType     = OutboxEventPublisher.EVENT_TYPE,
        payload       = payload,
        status        = status,
        createdAt     = LocalDateTime.now(ZoneOffset.UTC),
    )

    private fun tradePayload(tenantId: UUID, portfolioId: UUID) = OutboxEventPublisher.MAPPER.writeValueAsString(
        OutboxEventPublisher.TradeRecordedPayload(
            tradeId = UUID.randomUUID(), tenantId = tenantId, portfolioId = portfolioId,
            assetId = UUID.randomUUID(), price = BigDecimal("100.00"), tradeDate = LocalDate.of(2026, 7, 30),
        )
    )

    @Test
    fun `발행이 찍는 createdAt`() {
        val repo = mock(OutboxRepository::class.java)
        var saved: OutboxEventEntity? = null
        `when`(repo.save(any(OutboxEventEntity::class.java))).thenAnswer { inv ->
            saved = inv.getArgument(0); saved
        }

        inKstHost {
            OutboxEventPublisher(repo).publishTradeRecorded(
                tradeId = UUID.randomUUID(), tenantId = UUID.randomUUID(), portfolioId = UUID.randomUUID(),
                assetId = UUID.randomUUID(), price = BigDecimal("100.00"), tradeDate = LocalDate.of(2026, 7, 30),
            )
        }

        assertStampedInUtc(saved?.createdAt)
    }

    @Test
    fun `폴링 프로세서가 찍는 processedAt`() {
        val tenantId = UUID.randomUUID()
        val portfolioId = UUID.randomUUID()
        val event = entity(payload = tradePayload(tenantId, portfolioId))
        val repo = mock(OutboxRepository::class.java)
        `when`(repo.findRetryableForUpdate(OutboxEventProcessor.MAX_RETRIES)).thenReturn(listOf(event))
        val metrics = BrokerMetrics(SimpleMeterRegistry(), mock(DlqService::class.java), repo)

        inKstHost {
            OutboxEventProcessor(repo, mock(SnapshotTriggerService::class.java), metrics).process()
        }

        assertThat(event.status).isEqualTo(OutboxStatus.PROCESSED)
        assertStampedInUtc(event.processedAt)
    }

    @Test
    fun `실시간 리스너가 찍는 processedAt`() {
        val event = entity()
        val repo = mock(OutboxRepository::class.java)
        `when`(repo.findById(event.id)).thenReturn(Optional.of(event))
        val metrics = BrokerMetrics(SimpleMeterRegistry(), mock(DlqService::class.java), repo)

        inKstHost {
            TradeEventListener(mock(SnapshotTriggerService::class.java), repo, metrics).onTradeRecorded(
                TradeRecordedEvent(
                    outboxEventId = event.id, tenantId = UUID.randomUUID(), portfolioId = UUID.randomUUID(),
                    assetId = UUID.randomUUID(), price = BigDecimal("100.00"), tradeDate = LocalDate.of(2026, 7, 30),
                )
            )
        }

        assertThat(event.status).isEqualTo(OutboxStatus.PROCESSED)
        assertStampedInUtc(event.processedAt)
    }

    @Test
    fun `Kafka 리스너가 찍는 processedAt`() {
        val event = entity()
        val repo = mock(OutboxRepository::class.java)
        `when`(repo.findById(event.id)).thenReturn(Optional.of(event))

        inKstHost {
            TradeKafkaListener(mock(OutboxKafkaPublisher::class.java), repo).markProcessedKafka(
                TradeRecordedEvent(
                    outboxEventId = event.id, tenantId = UUID.randomUUID(), portfolioId = UUID.randomUUID(),
                    assetId = UUID.randomUUID(), price = BigDecimal("100.00"), tradeDate = LocalDate.of(2026, 7, 30),
                )
            )
        }

        assertThat(event.status).isEqualTo(OutboxStatus.PROCESSED_KAFKA)
        assertStampedInUtc(event.processedAt)
    }
}
