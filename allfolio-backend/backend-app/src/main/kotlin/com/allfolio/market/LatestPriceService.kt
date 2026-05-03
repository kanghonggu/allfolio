package com.allfolio.market

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 실시간 가격 저장 서비스
 *
 * 모든 WsAdapter → PriceUpdateEvent → 여기서 처리
 *   1. Redis SET price:latest:{exchange}:{symbol} {price} EX 300
 *   2. PriceSseRegistry 브로드캐스트 → 연결된 모든 프론트에 push
 *
 * @Async: WebSocket I/O 스레드 블로킹 없음
 */
@Service
class LatestPriceService(
    private val redisTemplate: StringRedisTemplate,
    private val sseRegistry: PriceSseRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onPriceUpdate(event: PriceUpdateEvent) {
        val redisKey = "price:latest:${event.exchange}:${event.symbol}"
        runCatching {
            redisTemplate.opsForValue().set(redisKey, event.price.toPlainString(), Duration.ofSeconds(300))
        }.onFailure { e ->
            log.warn("[LatestPrice] Redis write failed key={}: {}", redisKey, e.message)
        }

        if (sseRegistry.activeCount() == 0) return

        runCatching {
            val payload = mapOf(
                "exchange"  to event.exchange,
                "symbol"    to event.symbol,
                "price"     to event.price.toPlainString(),
                "timestamp" to event.timestamp,
            )
            sseRegistry.broadcast("price", objectMapper.writeValueAsString(payload))
        }.onFailure { e ->
            log.warn("[LatestPrice] SSE broadcast failed: {}", e.message)
        }
    }
}
