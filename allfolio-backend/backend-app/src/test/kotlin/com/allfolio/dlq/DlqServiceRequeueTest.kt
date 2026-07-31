package com.allfolio.dlq

import com.allfolio.broker.BrokerType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.Optional

class DlqServiceRequeueTest {

    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    private fun eventJson(): String = objectMapper.writeValueAsString(
        FailedTradeEvent(
            brokerType = "KIS", accountNo = "123", payloadType = FailedTradeEvent.TYPE_TRADE_COMMAND,
            payload = "{}", errorMessage = "boom", retryCount = 5,
        )
    )

    @Suppress("UNCHECKED_CAST")
    private fun service(listOps: ListOperations<String, String>): DlqService {
        val template = mock(StringRedisTemplate::class.java)
        `when`(template.opsForList()).thenReturn(listOps)
        return DlqService(template, objectMapper, SimpleMeterRegistry(), Optional.empty())
    }

    private fun anyKey(): String = anyString() ?: ""

    @Test
    fun `requeueDead - dead에서 pop한 만큼 main에 push하고 건수를 반환한다`() {
        @Suppress("UNCHECKED_CAST")
        val listOps = mock(ListOperations::class.java) as ListOperations<String, String>
        val json = eventJson()
        `when`(listOps.leftPop(anyKey())).thenReturn(json, json, null)

        val moved = service(listOps).requeueDead(BrokerType.KIS)

        assertEquals(2, moved)
        verify(listOps, org.mockito.Mockito.times(3)).leftPop("dlq:dead:KIS")
        verify(listOps, org.mockito.Mockito.times(2)).rightPush("dlq:trade:KIS", json)
    }

    @Test
    fun `peekDead - 역직렬화 실패 항목은 건너뛴다`() {
        @Suppress("UNCHECKED_CAST")
        val listOps = mock(ListOperations::class.java) as ListOperations<String, String>
        `when`(listOps.range(anyKey(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(listOf(eventJson(), "not-json"))

        val events = service(listOps).peekDead(BrokerType.KIS)

        assertEquals(1, events.size)
        assertEquals("KIS", events[0].brokerType)
        assertEquals(5, events[0].retryCount)
    }
}
