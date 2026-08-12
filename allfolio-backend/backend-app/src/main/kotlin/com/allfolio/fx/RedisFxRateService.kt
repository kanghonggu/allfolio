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
    @Value("\${fx.btc-krw.fallback-rate:90000000}")
    private val btcFallback: BigDecimal,
    @Value("\${fx.eth-krw.fallback-rate:4500000}")
    private val ethFallback: BigDecimal,
) : FxRateService {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY = "fx:usdtkrw"

        /**
         * 폴링 주기(기본 60초)의 3배.
         *
         * TTL이 폴링 주기와 같으면 안 된다. @Scheduled(fixedDelay)는 직전 실행이 *끝난*
         * 시점부터 재므로 다음 쓰기는 항상 `주기 + fetch 시간` 뒤에 일어나는데,
         * 키는 정확히 주기에 만료된다 — 즉 매 주기 갱신 직전에 반드시 만료 창이 생기고
         * 그 동안 수집기가 멀쩡한데도 폴백 상수가 반환된다.
         *
         * 3배로 두면 연속 2회 실패까지는 마지막 정상 환율을 지킨다.
         */
        private val TTL = Duration.ofSeconds(180)
    }

    private fun cryptoKey(symbol: String) = "fx:${symbol.lowercase()}krw"
    private fun cryptoFallback(symbol: String): BigDecimal = when (symbol.uppercase()) {
        "BTC" -> btcFallback
        "ETH" -> ethFallback
        else  -> throw IllegalArgumentException("지원하지 않는 코인: $symbol")
    }

    override fun getCryptoToKrw(symbol: String): BigDecimal {
        val fallback = cryptoFallback(symbol)   // 미지원 심볼이면 여기서 예외
        return runCatching {
            redisTemplate.opsForValue().get(cryptoKey(symbol))?.let { BigDecimal(it) } ?: run {
                log.warn("[FxRate] Redis miss {}krw — fallback={}", symbol, fallback); fallback
            }
        }.getOrElse { e ->
            log.warn("[FxRate] Redis error {}krw — fallback={}: {}", symbol, fallback, e.message); fallback
        }
    }

    override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {
        cryptoFallback(symbol)   // 심볼 검증
        runCatching { redisTemplate.opsForValue().set(cryptoKey(symbol), rate.toPlainString(), TTL) }
            .onFailure { e -> log.error("[FxRate] Redis SET {}krw failed: {}", symbol, e.message) }
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
