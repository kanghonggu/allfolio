package com.allfolio.fx.exchange

import com.allfolio.fx.FxApiClient
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * 유일한 [FxApiClient] 빈. 거래소 소스를 순서대로 시도하되 **심볼 단위로 해소한다.**
 *
 * 앞선 소스가 채우지 못한 심볼만 다음 소스에서 받는다. 한 심볼이 실패했다고 전체를
 * 버리면 그 하나 때문에 나머지 심볼의 Redis 값이 낡는다.
 *
 * 하나도 못 채웠을 때만 예외를 던진다 — [com.allfolio.fx.FxRateScheduler]가 그 예외를 잡아
 * 기존 Redis 값을 지키는 계약을 그대로 유지한다.
 */
class ExchangeFxApiClient(
    private val sources: List<FxQuoteSource>,
) : FxApiClient {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 심볼별 타당 범위.
         *
         * 이 가드가 잡으려는 것은 "시세가 이상하다"가 아니라 **"파싱이 깨졌다"**이다.
         * 0이나 타임스탬프가, 혹은 BTC 자리에 USDT 값이 들어오면 실패보다 나쁘다 —
         * 예외 없이 모든 자산 평가를 오염시킨다.
         *
         * 좁게 잡으면 실제 급변동 때 시세가 얼어붙으므로 일부러 넓게 둔다.
         * (2026-08-12 실측: USDT 1409 · BTC 89,825,000 · ETH 2,663,000)
         */
        private val RANGES: Map<String, ClosedRange<BigDecimal>> = mapOf(
            FxSymbols.USDT to BigDecimal("500")..BigDecimal("5000"),
            FxSymbols.BTC to BigDecimal("1000000")..BigDecimal("1000000000"),
            FxSymbols.ETH to BigDecimal("100000")..BigDecimal("100000000"),
        )
    }

    override fun fetchKrwRates(): Map<String, BigDecimal> {
        val resolved = mutableMapOf<String, BigDecimal>()
        var lastFailure: FxQuoteException? = null

        for (source in sources) {
            if (resolved.keys.containsAll(FxSymbols.ALL)) break

            val fetched = try {
                source.fetchKrwRates()
            } catch (e: FxQuoteException) {
                log.warn("[ExchangeFx] {} 실패: {}", source.sourceName, e.message)
                lastFailure = e
                continue
            }

            for ((symbol, rate) in fetched) {
                if (symbol in resolved) continue          // 앞선 소스가 이미 채웠다

                val range = RANGES[symbol] ?: continue    // 우리가 안 쓰는 심볼
                if (rate !in range) {
                    // 예외를 안 던지고 값을 돌려준 소스가 범위를 벗어났다 = 파싱이 깨졌다는 뜻이다.
                    log.warn("[ExchangeFx] {} {} 값이 범위 밖이라 무시: {}", source.sourceName, symbol, rate)
                    lastFailure = FxQuoteException("${source.sourceName} $symbol 범위 밖: $rate")
                    continue
                }

                resolved[symbol] = rate
            }

            log.info("[ExchangeFx] source={} 해소={}", source.sourceName, resolved.keys)
        }

        if (resolved.isEmpty()) {
            // cause를 붙이는 이유: 스케줄러는 e.message만 찍는다. 원인을 안 달면
            // 전량 실패했을 때 로그 한 줄로는 왜 실패했는지 알 수 없다.
            throw FxQuoteException(
                "모든 소스에서 KRW 시세를 가져오지 못했습니다 (시도=${sources.size})",
                lastFailure,
            )
        }

        val missing = FxSymbols.ALL - resolved.keys
        if (missing.isNotEmpty()) {
            // 부분 성공은 실패가 아니다. 다만 조용히 넘어가면 특정 심볼만 영영 낡는다.
            log.warn("[ExchangeFx] 끝내 못 채운 심볼={} — 그 심볼은 기존 값이 유지된다", missing)
        }

        return resolved
    }
}
