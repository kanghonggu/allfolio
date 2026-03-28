package com.allfolio.broker

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 브로커별 Rate Limiter — Redis INCR 기반, Non-blocking
 *
 * 설계 원칙:
 * - Thread.sleep 없음 — Scheduler 스레드 절대 블로킹하지 않음
 * - Redis INCR + EXPIRE: 1초 슬라이딩 윈도우
 * - Redis 장애 시 허용 (fail-open)
 * - tryAcquire() 반환: true=허용, false=초과(호출자가 skip)
 *
 * Redis key: "rl:{key}:{epochSeconds}"  TTL: 2s
 *
 * 성능 영향: Redis INCR 1회 (O(1)) — Trade write path 완전 분리
 */
@Component
class BrokerRateLimiter(
    private val redisTemplate: RedisTemplate<String, Any>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 브로커별 초당 최대 호출 수 */
    private val limits = mapOf(
        BrokerType.BINANCE to 5,
        BrokerType.TOSS    to 2,
        BrokerType.SAMSUNG to 2,
    )

    /**
     * Rate limit 획득 시도 — non-blocking
     * @return true: 허용, false: 초과 (호출자가 skip 처리)
     */
    fun tryAcquire(brokerType: BrokerType, key: String = brokerType.name): Boolean {
        val limit    = limits[brokerType] ?: 2
        val window   = System.currentTimeMillis() / 1000L
        val redisKey = "rl:$key:$window"

        return runCatching {
            val count = redisTemplate.opsForValue().increment(redisKey) ?: 1L
            if (count == 1L) redisTemplate.expire(redisKey, Duration.ofSeconds(2))
            if (count > limit) {
                log.debug("[RateLimit] blocked key={} count={}/{}", key, count, limit)
                false
            } else true
        }.getOrElse { e ->
            log.warn("[RateLimit] Redis error, fail-open key={}", key, e)
            true  // fail-open: Redis 장애 시 허용
        }
    }
}
