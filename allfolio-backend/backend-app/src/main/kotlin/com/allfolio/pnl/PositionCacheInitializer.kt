package com.allfolio.pnl

import com.allfolio.broker.BrokerSyncStateRepository
import com.allfolio.trade.domain.TradeType
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID

/**
 * 서버 기동 시 포지션 캐시 초기화
 *
 * 흐름:
 * 1. BrokerSyncState에서 portfolioId 목록 조회
 * 2. trade_raw에서 해당 포트폴리오의 전체 거래 이력 로드
 * 3. BUY/SELL 순서대로 적용 → 현재 포지션 계산
 * 4. Redis Hash(pnl:positions:{portfolioId}) 초기화
 *
 * 성능:
 * - @Async: ApplicationRunner는 startup 블로킹 → @Async로 백그라운드 실행
 * - 초기화 중 가격 이벤트 수신 시 positionCache hit 없음 → PnL 계산 skip (정상)
 * - 초기화 완료 후 실시간 PnL 계산 정상 동작
 *
 * 주의: portfolio당 trade 수가 많으면 (>10만건) 초기화 시간 증가
 *       이 경우 마지막 Snapshot에서 포지션 역산 로직으로 교체 권장
 */
@Component
class PositionCacheInitializer(
    private val tradeRepository: TradeRawJpaRepository,
    private val syncStateRepository: BrokerSyncStateRepository,
    private val positionCacheService: PositionCacheService,
    private val redisProperties: RedisProperties,
    @Value("\${allfolio.position-init.redis-max-attempts:5}")
    private val redisMaxAttempts: Int = 5,
    @Value("\${allfolio.position-init.redis-retry-initial-delay-ms:1000}")
    private val redisRetryInitialDelayMs: Long = 1000,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    override fun run(args: ApplicationArguments) {
        if (!waitForRedis()) return

        val portfolioIds = runCatching {
            syncStateRepository.findAll().map { it.id.portfolioId }.distinct()
        }.getOrElse { e ->
            log.warn("[PositionInit] failed to load portfolioIds", e)
            emptyList()
        }

        if (portfolioIds.isEmpty()) {
            log.info("[PositionInit] no portfolios to initialize")
            return
        }

        log.info("[PositionInit] initializing position cache for {} portfolios", portfolioIds.size)

        portfolioIds.forEach { portfolioId ->
            runCatching { initPortfolio(portfolioId) }
                .onFailure { e -> log.error("[PositionInit] failed for portfolioId={}: {}", portfolioId, e.message, e) }
        }

        log.info("[PositionInit] completed for {} portfolios", portfolioIds.size)
    }

    /**
     * Redis 준비 대기 — PING 성공까지 지수 백오프 재시도.
     *
     * 콜드 부트(0.1 CPU) 직후 첫 Lettuce 연결은 DNS+TCP+핸드셰이크가 느려 실패할 수 있고,
     * 이 initializer가 첫 Redis 호출이라 실패를 그대로 흡수하면 캐시가 영영 비게 된다.
     */
    private fun waitForRedis(): Boolean {
        val target = "${redisProperties.host}:${redisProperties.port}"

        for (attempt in 1..redisMaxAttempts) {
            val error = runCatching { positionCacheService.ping() }.exceptionOrNull()
                ?: run {
                    if (attempt > 1) log.info("[PositionInit] Redis ready after {} attempts (target={})", attempt, target)
                    return true
                }

            if (attempt == redisMaxAttempts) {
                log.error(
                    "[PositionInit] Redis unreachable after {} attempts (target={}) — position cache stays empty until restart",
                    redisMaxAttempts, target, error,
                )
                return false
            }

            val backoffMs = redisRetryInitialDelayMs shl (attempt - 1)
            log.warn(
                "[PositionInit] Redis not ready (attempt {}/{}, target={}): {} — retrying in {}ms",
                attempt, redisMaxAttempts, target, error.message, backoffMs,
            )
            Thread.sleep(backoffMs)
        }
        return false
    }

    private fun initPortfolio(portfolioId: UUID) {
        val trades = tradeRepository
            .findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(portfolioId, LocalDateTime.now())

        if (trades.isEmpty()) return

        // 누적 포지션 계산 (평균가 방식)
        val positions = mutableMapOf<UUID, MutablePositionState>()

        trades.forEach { trade ->
            val state = positions.getOrPut(trade.assetId) { MutablePositionState() }

            when (trade.tradeType) {
                TradeType.BUY -> {
                    val newQty     = state.quantity + trade.quantity
                    val newAvgCost = if (newQty > BigDecimal.ZERO) {
                        (state.quantity * state.avgCost + trade.quantity * trade.price)
                            .divide(newQty, 10, RoundingMode.HALF_UP)
                    } else BigDecimal.ZERO
                    state.quantity = newQty
                    state.avgCost  = newAvgCost
                }
                TradeType.SELL -> {
                    state.quantity = (state.quantity - trade.quantity).max(BigDecimal.ZERO)
                    if (state.quantity <= BigDecimal.ZERO) state.avgCost = BigDecimal.ZERO
                }
            }
        }

        // 보유 포지션만 (quantity > 0) Redis에 저장
        val positionMap = positions
            .filter { it.value.quantity > BigDecimal.ZERO }
            .mapValues { (assetId, state) ->
                PositionData(portfolioId, assetId, state.quantity, state.avgCost)
            }

        positionCacheService.initPositions(portfolioId, positionMap)
        log.info("[PositionInit] portfolioId={} positions={} trades={}", portfolioId, positionMap.size, trades.size)
    }

    private data class MutablePositionState(
        var quantity: BigDecimal = BigDecimal.ZERO,
        var avgCost: BigDecimal  = BigDecimal.ZERO,
    )
}
