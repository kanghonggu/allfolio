package com.allfolio.fx

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Duration

/**
 * Redis 기반 시세 캐시 서비스
 *
 * Redis key: fx:usdtkrw · fx:btckrw · fx:ethkrw
 * TTL: USDT 180초(폴링 주기의 3배) · 코인 24시간. 아래 TTL 상수 주석 참조.
 *
 * 폴백 우선순위:
 *   1. Redis 캐시
 *   2. **USDT만** 설정값 (FX_USDT_KRW_FALLBACK, 기본값 1400)
 *
 * **코인에는 폴백 상수가 없다.** 상수를 두면 갱신 주체가 사라진 순간 조용히 틀린 값이
 * 평가에 들어간다 — ETH가 4,500,000으로 박혀 있어 실제 2,663,000 대비 69% 과대평가였다.
 * 값이 없으면 IllegalStateException을 던진다. 수집기가 60초마다 채우므로 이 예외는
 * 두 거래소가 동시에 죽어 있는 동안에만 나온다.
 */
@Service
class RedisFxRateService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${fx.usdt-krw.fallback-rate:1400}")
    private val fallbackRate: BigDecimal,
) : FxRateService {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY = "fx:usdtkrw"

        /** 코인은 이 목록만 다룬다. CurrencyConverter의 "BTC","ETH" 분기와 짝이다. */
        private val CRYPTO_SUPPORTED = setOf("BTC", "ETH")

        /**
         * 폴링 주기(기본 60초)의 3배.
         *
         * TTL이 폴링 주기와 같으면 안 된다. @Scheduled(fixedDelay)는 직전 실행이 *끝난*
         * 시점부터 재므로 다음 쓰기는 항상 `주기 + fetch 시간` 뒤에 일어나는데,
         * 키는 정확히 주기에 만료된다 — 즉 매 주기 갱신 직전에 반드시 만료 창이 생기고
         * 그 동안 수집기가 멀쩡한데도 폴백 상수가 반환된다.
         *
         * 3배로 두면 연속 2회 실패까지는 마지막 정상 환율을 지킨다.
         *
         * 이 값은 fx.scheduler.delay-ms(기본 60초)와 짝이다. 한쪽만 바꾸면 이 관계가 깨지는데
         * 컴파일러도 테스트도 잡아 주지 않는다.
         */
        private val TTL_USDT = Duration.ofSeconds(180)

        /**
         * 코인은 24시간.
         *
         * USDT와 사정이 다르다. USDT는 폴백 상수가 있어 만료가 "상수로 떨어짐"을 뜻하지만,
         * 코인은 상수가 없으므로 만료가 곧 **데이터 없음**, 즉 평가가 예외로 죽는다는 뜻이다.
         * 60초마다 덮어쓰므로 24시간 만료는 "수집이 하루 종일 죽어 있었다"는 뜻이고,
         * 그건 만료보다 훨씬 먼저 드러나야 할 사건이다.
         */
        private val TTL_CRYPTO = Duration.ofHours(24)
    }

    private fun cryptoKey(symbol: String) = "fx:${symbol.lowercase()}krw"

    /** 상수가 사라졌으므로 심볼 검증을 따로 한다 — 예전엔 cryptoFallback이 겸하고 있었다. */
    private fun requireSupported(symbol: String): String {
        val upper = symbol.uppercase()
        require(upper in CRYPTO_SUPPORTED) { "지원하지 않는 코인: $symbol" }
        return upper
    }

    override fun getCryptoToKrw(symbol: String): BigDecimal {
        val upper = requireSupported(symbol)

        val cached = runCatching { redisTemplate.opsForValue().get(cryptoKey(upper)) }
            .getOrElse { e ->
                log.error("[FxRate] Redis error {}krw: {}", upper, e.message)
                null
            }

        // 상수를 돌려주지 않는다. 없는 값을 지어내면 조용히 틀린 평가가 나간다.
        return cached?.let { BigDecimal(it) }
            ?: throw IllegalStateException(
                "$upper KRW 시세가 없습니다 — 수집기가 도는지 [ExchangeFx] 로그를 확인하십시오"
            )
    }

    override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {
        val upper = requireSupported(symbol)
        runCatching { redisTemplate.opsForValue().set(cryptoKey(upper), rate.toPlainString(), TTL_CRYPTO) }
            .onFailure { e -> log.error("[FxRate] Redis SET {}krw failed: {}", upper, e.message) }
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
            redisTemplate.opsForValue().set(KEY, rate.toPlainString(), TTL_USDT)
            log.info("[FxRate] updated usdtkrw={}", rate)
        }.onFailure { e ->
            log.error("[FxRate] Redis SET failed rate={}: {}", rate, e.message)
        }
    }
}
