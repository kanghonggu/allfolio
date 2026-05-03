package com.allfolio.price

import com.allfolio.market.PriceUpdateEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * WebSocket 틱 데이터 배치 저장기
 *
 * 설계 원칙:
 *   - PriceUpdateEvent 수신 → 메모리 큐에만 적재 (논블로킹, O(1))
 *   - @Scheduled(100ms): 큐 drain → 배치 INSERT (DB 왕복 최소화)
 *   - ConcurrentLinkedQueue: lock-free, 다중 스레드 안전
 *   - 배치 크기: MAX_BATCH 초과 시 초과분 버림 (백프레셔)
 *
 * Redis와의 역할 분리:
 *   - Redis (pnl:latest:*): 실시간 최신 PnL — TTL 5분, 조회 최적화
 *   - PostgreSQL (market_price_tick): 전체 틱 히스토리 — 분석/백테스트용
 */
@Component
@ConditionalOnProperty(name = ["market.tick.db-enabled"], havingValue = "true")
class MarketPriceBatchWriter(
    private val repository: MarketPriceTickRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val buffer = ConcurrentLinkedQueue<MarketPriceTickEntity>()

    @Async
    @EventListener
    fun onPriceUpdate(event: PriceUpdateEvent) {
        if (buffer.size >= MAX_BATCH * 10) return  // 백프레셔: 버퍼 과적 방지

        buffer.offer(
            MarketPriceTickEntity(
                exchange       = event.exchange,
                symbol         = event.symbol,
                price          = event.price,
                tickTimestamp  = Instant.ofEpochMilli(event.timestamp),
            )
        )
    }

    /** 100ms마다 버퍼 drain → 배치 INSERT */
    @Scheduled(fixedDelay = 100)
    fun flush() {
        if (buffer.isEmpty()) return

        val batch = mutableListOf<MarketPriceTickEntity>()
        repeat(MAX_BATCH) {
            val entity = buffer.poll() ?: return@repeat
            batch.add(entity)
        }
        if (batch.isEmpty()) return

        runCatching {
            repository.saveAll(batch)
            log.debug("[PriceBatch] flushed {} ticks", batch.size)
        }.onFailure { e ->
            log.warn("[PriceBatch] flush failed: {}", e.message)
            // 실패한 배치는 드롭 (재시도 없음 — 틱 데이터는 손실 허용)
        }
    }

    companion object {
        private const val MAX_BATCH = 500
    }
}
