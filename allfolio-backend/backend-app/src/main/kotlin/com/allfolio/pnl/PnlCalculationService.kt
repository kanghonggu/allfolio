package com.allfolio.pnl

import com.allfolio.market.PriceUpdateEvent
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.broker.BrokerSyncStateRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * 실시간 PnL 계산 서비스
 *
 * 흐름:
 *   BinanceWsAdapter → PriceUpdateEvent (publish)
 *       ↓ @Async @EventListener (Spring ThreadPoolTaskExecutor)
 *   PnlCalculationService.onPriceUpdate()
 *       ↓ PositionCacheService.getPositions() (Redis HGETALL)
 *       ↓ PnL 계산: (currentPrice - avgCost) * quantity
 *       ↓ PortfolioUpdateEvent 발행 (SSE/WebSocket 연동 예정)
 *       ↓ Redis에 최신 PnL 스냅샷 저장 (선택)
 *
 * 성능 원칙:
 * - @Async: WebSocket I/O 스레드 블로킹 없음
 * - DB 접근 완전 없음 — Redis only
 * - 동일 symbol 연속 이벤트: 최신값만 중요 → last-write-wins
 *
 * Redis key: pnl:latest:{portfolioId}:{assetId}  TTL 5분
 */
@Service
class PnlCalculationService(
    private val positionCacheService: PositionCacheService,
    private val syncStateRepository: BrokerSyncStateRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val redisTemplate: StringRedisTemplate,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 가격 업데이트 수신 → PnL 재계산
     *
     * @Async: OkHttp WebSocket 스레드를 블로킹하지 않음
     * @EventListener: BinanceWsAdapter.handleMessage() → publishEvent() 후 즉시 반환
     */
    @Async
    @EventListener
    fun onPriceUpdate(event: PriceUpdateEvent) {
        // BrokerSyncState에서 해당 assetId를 보유한 portfolioId 목록 조회
        // (쿼리 1회, 결과는 수십 건 이하 — 경량)
        val portfolioIds = runCatching {
            syncStateRepository.findAll()
                .map { it.id.portfolioId }
                .distinct()
        }.getOrElse { emptyList() }

        portfolioIds.forEach { portfolioId ->
            val position = positionCacheService.getPosition(portfolioId, event.assetId) ?: return@forEach

            if (position.quantity <= BigDecimal.ZERO) return@forEach

            val pnl    = (event.price - position.avgCost) * position.quantity
            val cost   = position.avgCost * position.quantity
            val pnlPct = if (cost.compareTo(BigDecimal.ZERO) != 0) {
                pnl.divide(cost, 4, RoundingMode.HALF_UP) * BigDecimal("100")
            } else BigDecimal.ZERO

            val update = PortfolioUpdateEvent(
                portfolioId    = portfolioId,
                assetId        = event.assetId,
                symbol         = event.symbol,
                quantity       = position.quantity,
                avgCost        = position.avgCost,
                currentPrice   = event.price,
                unrealizedPnl  = pnl,
                unrealizedPnlPct = pnlPct,
            )

            // Redis에 최신 PnL 저장 (REST API 조회용, TTL 5분)
            runCatching {
                val key = "pnl:latest:$portfolioId:${event.assetId}"
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(update), Duration.ofMinutes(5))
            }

            // PortfolioUpdateEvent 발행 (SSE/WebSocket 연동 예정)
            eventPublisher.publishEvent(update)

            metrics.pnlCalculated(portfolioId.toString())

            log.debug("[PnL] portfolio={} symbol={} price={} pnl={} ({} %)",
                portfolioId, event.symbol, event.price, pnl.setScale(2, RoundingMode.HALF_UP), pnlPct)
        }
    }
}
