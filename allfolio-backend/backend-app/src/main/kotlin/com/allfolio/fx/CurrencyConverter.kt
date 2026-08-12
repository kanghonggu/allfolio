package com.allfolio.fx

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 통화 변환 유틸리티
 *
 * KRW 기준 통합 — 모든 가격을 KRW 원화 기준으로 환산.
 * 지원 통화: KRW (1:1), USD (공식 매매기준율), USDT (거래소 시세), BTC·ETH (코인당 KRW 시세)
 * 미지원 통화: KRW 그대로 반환 + 경고 로그
 */
@Component
class CurrencyConverter(
    private val fxRateService: FxRateService,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * 금액을 KRW로 환산한다.
     *
     * @param amount   환산 전 금액
     * @param currency "KRW" | "USDT" (대소문자 구분 없음)
     * @return KRW 환산금액 (소수점 0자리, HALF_UP 반올림)
     */
    fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        when (currency.uppercase()) {
            "KRW"  -> amount
            // AF-99: USD와 USDT는 **의도적으로 다른 환율**을 쓴다.
            //
            // USDT는 거래소 시세다. 김치 프리미엄이 섞여 있지만 그건 "부정확"이 아니라
            // 거래소에 실제 USDT를 들고 있는 사용자에게 실현 가능한 값이다. 공식 고시로
            // 바꾸면 더 정확해 보이지만 그 계정에서는 덜 현실적이 된다.
            //
            // 한쪽으로 접지 말 것. 하나은행 고시가 없는 동안은 getUsdToKrw()의 default가
            // getUsdtToKrw()라 두 값이 같지만, 그건 폴백이지 같은 개념이라서가 아니다.
            "USD" -> {
                val rate = fxRateService.getUsdToKrw()
                (amount * rate).setScale(0, RoundingMode.HALF_UP)
            }
            "USDT" -> {
                val rate = fxRateService.getUsdtToKrw()
                (amount * rate).setScale(0, RoundingMode.HALF_UP)
            }
            // QA P3: BTC/ETH도 코인당 KRW 시세로 환산 — 1:1 폴백은 0.5 BTC를 0.5원으로 축소하던 버그
            "BTC", "ETH" -> {
                val price = fxRateService.getCryptoToKrw(currency.uppercase())
                (amount * price).setScale(0, RoundingMode.HALF_UP)
            }
            else   -> {
                log.warn("[CurrencyConverter] unsupported currency={} — returning as-is", currency)
                amount
            }
        }

    /**
     * marketPrices(Map<assetId, price>)를 tradeCurrency 기준으로 KRW 환산.
     * tradeCurrency가 모두 동일하다는 가정 하에 일괄 변환.
     */
    fun convertPricesToKrw(
        prices: Map<java.util.UUID, BigDecimal>,
        currency: String,
    ): Map<java.util.UUID, BigDecimal> {
        if (currency.uppercase() == "KRW") return prices
        return prices.mapValues { (_, price) -> toKrw(price, currency) }
    }
}
