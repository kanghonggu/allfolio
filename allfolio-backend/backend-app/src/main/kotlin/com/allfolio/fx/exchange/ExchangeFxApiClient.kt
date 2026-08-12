package com.allfolio.fx.exchange

import com.allfolio.fx.FxApiClient
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * 유일한 [FxApiClient] 빈. 거래소 소스를 순서대로 시도한다.
 *
 * 전부 실패했을 때만 예외를 던진다 — [com.allfolio.fx.FxRateScheduler]가 그 예외를 잡아
 * 기존 Redis 값을 지키는 기존 계약을 그대로 유지한다.
 *
 * 소스를 FxApiClient로 직접 만들지 않은 이유는 빈이 둘이 되면 스케줄러의 주입이 깨지기 때문이다.
 */
class ExchangeFxApiClient(
    private val sources: List<FxQuoteSource>,
) : FxApiClient {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 타당한 USDT/KRW 범위.
         *
         * 이 가드가 잡으려는 것은 "환율이 이상하다"가 아니라 **"파싱이 깨졌다"**이다.
         * 0이나 타임스탬프가 환율 자리에 들어오면 실패보다 나쁘다 — 예외 없이
         * 모든 자산 평가를 오염시키기 때문이다.
         *
         * 좁게 잡으면 실제 급변동 때 환율이 얼어붙으므로 일부러 넓게 둔다.
         * (2026-08-12 실측 1408, 52주 범위 1362~1655)
         */
        private val MIN_RATE = BigDecimal("500")
        private val MAX_RATE = BigDecimal("5000")
    }

    override fun getUsdtKrw(): BigDecimal {
        var lastFailure: FxQuoteException? = null

        for (source in sources) {
            val rate = try {
                source.fetchUsdtKrw()
            } catch (e: FxQuoteException) {
                log.warn("[ExchangeFx] {} 실패: {}", source.sourceName, e.message)
                lastFailure = e
                continue
            }

            if (rate < MIN_RATE || rate > MAX_RATE) {
                // 예외를 안 던지고 값을 돌려준 소스가 범위를 벗어났다 = 파싱이 깨졌다는 뜻이다.
                log.warn("[ExchangeFx] {} 값이 범위 밖이라 무시: {}", source.sourceName, rate)
                lastFailure = FxQuoteException("${source.sourceName} 값이 범위 밖: $rate")
                continue
            }

            log.info("[ExchangeFx] source={} USDTKRW={}", source.sourceName, rate)
            return rate
        }

        // cause를 붙이는 이유: 스케줄러는 e.message만 찍는다. 원인을 안 달면
        // 전량 실패했을 때 로그 한 줄로는 왜 실패했는지 알 수 없고,
        // 위쪽 WARN 줄과 눈으로 짝지어야 한다 — 로그 파이프라인에서는 그 줄이 없을 수도 있다.
        throw FxQuoteException(
            "모든 소스에서 USDT/KRW를 가져오지 못했습니다 (시도=${sources.size})",
            lastFailure,
        )
    }
}
