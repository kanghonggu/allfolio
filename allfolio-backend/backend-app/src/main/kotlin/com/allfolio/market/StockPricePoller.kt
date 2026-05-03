package com.allfolio.market

import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.infrastructure.adapter.YahooFinanceClient
import com.allfolio.unifiedasset.infrastructure.jpa.AssetJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 한국 주식 시세 폴링 (Yahoo Finance, 1분 간격)
 *
 * - 장 시간(09:00~15:30 KST, 평일)에만 실행
 * - PriceUpdateEvent 발행 → Redis → SSE → 프론트 실시간 반영
 * - API key 불필요, 나중에 KIS WebSocket으로 교체 가능
 */
@Component
class StockPricePoller(
    private val assetJpaRepository: AssetJpaRepository,
    private val yahooFinanceClient: YahooFinanceClient,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val KST = ZoneId.of("Asia/Seoul")

    @Scheduled(fixedDelay = 60_000)
    fun poll() {
        if (!isMarketOpen()) return

        val symbols = assetJpaRepository.findAll()
            .filter { it.type == AssetType.STOCK && it.symbol != null }
            .mapNotNull { it.symbol }
            .distinct()

        if (symbols.isEmpty()) return

        log.debug("[StockPoller] polling {} symbols", symbols.size)

        for (symbol in symbols) {
            val price = yahooFinanceClient.getPrice(symbol) ?: continue
            eventPublisher.publishEvent(
                PriceUpdateEvent(
                    exchange  = "STOCK",
                    symbol    = symbol,
                    assetId   = UUID.nameUUIDFromBytes("stock:$symbol".toByteArray()),
                    price     = price,
                    timestamp = System.currentTimeMillis(),
                )
            )
            log.debug("[StockPoller] {} price={}", symbol, price)
        }
    }

    private fun isMarketOpen(): Boolean {
        val now = ZonedDateTime.now(KST)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val timeMin = now.hour * 60 + now.minute
        // NXT 기준: 프리마켓 08:00 ~ 08:50, 정규장 09:00 ~ 15:30, 애프터마켓 15:40 ~ 20:00
        return timeMin in (8 * 60)..(20 * 60)
    }
}
