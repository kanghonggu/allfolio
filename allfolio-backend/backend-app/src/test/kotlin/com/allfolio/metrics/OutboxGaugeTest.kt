package com.allfolio.metrics

import com.allfolio.dlq.DlqService
import com.allfolio.trade.infrastructure.outbox.OutboxEventEntity
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * outbox.{pending,failed,dead}.size Gauge는 실제 건수를 내보내야 한다.
 *
 * 예전 구현은 findTop100…().size로 재서 100에서 멈췄다 — 알림 `pending > 1000`·`failed > 100`은
 * 그 값으로는 영원히 울릴 수 없었다. 저장소가 상위 100건까지만 돌려주는 상황을 그대로 두고
 * Gauge가 그 크기가 아니라 count를 읽는지 본다.
 */
class OutboxGaugeTest {

    private fun registryWith(counts: Map<OutboxStatus, Long>): SimpleMeterRegistry {
        val repo = Mockito.mock(OutboxRepository::class.java)
        val top100 = List(100) { Mockito.mock(OutboxEventEntity::class.java) }
        counts.forEach { (status, count) ->
            Mockito.`when`(repo.countByStatus(status)).thenReturn(count)
            Mockito.`when`(repo.findTop100ByStatusOrderByCreatedAtAsc(status)).thenReturn(top100)
        }
        val registry = SimpleMeterRegistry()
        BrokerMetrics(registry, Mockito.mock(DlqService::class.java), repo).registerGauges()
        return registry
    }

    private fun SimpleMeterRegistry.gauge(name: String) = get(name).gauge().value()

    @Test
    fun `건수가 100을 넘어도 Gauge는 실제 건수를 낸다 — 알림 임계값에 닿을 수 있어야 한다`() {
        val registry = registryWith(
            mapOf(
                OutboxStatus.PENDING to 1_500L,
                OutboxStatus.FAILED  to 101L,
                OutboxStatus.DEAD    to 250L,
            )
        )

        assertEquals(1_500.0, registry.gauge("outbox.pending.size"))
        assertEquals(101.0,   registry.gauge("outbox.failed.size"))
        assertEquals(250.0,   registry.gauge("outbox.dead.size"))
    }
}
