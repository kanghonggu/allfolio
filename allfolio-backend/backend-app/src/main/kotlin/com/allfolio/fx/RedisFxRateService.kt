package com.allfolio.fx

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration

/**
 * Redis 기반 환율 캐시 서비스
 *
 * Redis key: fx:usdtkrw
 * TTL: 60초 (갱신 주기와 별개 — 어드민 SET 시 TTL 리셋)
 *
 * 폴백 우선순위:
 *   1. Redis 캐시
 *   2. 환경 변수 (USDT_KRW_FALLBACK_RATE, 기본값 1350)
 */
@Service
class RedisFxRateService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${fx.usdt-krw.fallback-rate:1350}")
    private val fallbackRate: BigDecimal,
) : FxRateService {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY = "fx:usdtkrw"
        private val TTL = Duration.ofSeconds(60)
    }

    override fun getUsdtToKrw(): BigDecimal =
        runCatching {
            val cached = redisTemplate.opsForValue().get(KEY)
            if (cached != null) BigDecimal(cached)
            else {
                log.warn("[FxRate] Redis miss — using fallback rate={}", fallbackRate)
                fallbackRate
            }
        }.getOrElse { e ->
            log.warn("[FxRate] Redis error — using fallback rate={}: {}", fallbackRate, e.message)
            fallbackRate
        }

    override fun setUsdtToKrw(rate: BigDecimal) {
        runCatching {
            redisTemplate.opsForValue().set(KEY, rate.toPlainString(), TTL)
            log.info("[FxRate] updated usdtkrw={}", rate)
        }.onFailure { e ->
            log.error("[FxRate] Redis SET failed rate={}: {}", rate, e.message)
        }
    }
}
