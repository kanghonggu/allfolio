package com.allfolio.marketdata.price

import com.allfolio.marketdata.adapter.InternalPriceEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * InternalPriceEvent → buffer → 100ms batch INSERT
 *
 * DB write를 직접 하지 않고 큐에 쌓고 스케줄러가 일괄 처리 → INSERT 횟수 최소화
 */
@Component
class MarketPriceBatchWriter(
    private val repository: MarketPriceTickRepository,
) {
    private val log    = LoggerFactory.getLogger(javaClass)
    private val buffer = ConcurrentLinkedQueue<MarketPriceTickEntity>()

    @Async("wsEventExecutor")
    @EventListener
    fun onPrice(event: InternalPriceEvent) {
        buffer.add(
            MarketPriceTickEntity(
                exchange      = event.exchange,
                symbol        = event.symbol,
                price         = event.price,
                volume        = event.volume,
                tickTimestamp = Instant.ofEpochMilli(event.timestamp),
            )
        )
    }

    @Scheduled(fixedDelay = 100)
    fun flush() {
        if (buffer.isEmpty()) return
        val batch = mutableListOf<MarketPriceTickEntity>()
        while (true) batch.add(buffer.poll() ?: break)
        if (batch.isEmpty()) return
        runCatching { repository.saveAll(batch) }
            .onSuccess { log.debug("[BatchWriter] flushed {} ticks", batch.size) }
            .onFailure { e -> log.error("[BatchWriter] flush failed: {}", e.message) }
    }
}
