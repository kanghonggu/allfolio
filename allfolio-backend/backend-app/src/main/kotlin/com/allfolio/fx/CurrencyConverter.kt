package com.allfolio.fx

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 통화 변환 유틸리티
 *
 * KRW 기준 통합 — 모든 가격을 KRW 원화 기준으로 환산.
 * 지원 통화: KRW(1:1) · USD(하나은행 매매기준율) · USDT(거래소 시세) · BTC/ETH(코인 시세)
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
     * @param currency "KRW" | "USD" | "USDT" | "BTC" | "ETH" (대소문자 구분 없음, 공백은 못 봐준다)
     * @return KRW 환산금액 (소수점 0자리, HALF_UP 반올림)
     */
    fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        when (currency.uppercase()) {
            "KRW"  -> amount
            // AF-99: 법정통화 USD는 하나은행 공식 매매기준율.
            // 1:1(원화 취급) 폴백은 달러 자산을 1/1400로 축소하는 버그였다
            "USD" -> {
                val rate = fxRateService.getUsdToKrw()
                (amount * rate).setScale(0, RoundingMode.HALF_UP)
            }
            // 스테이블코인은 거래소 시세를 유지한다 — 김치 프리미엄은 부정확이 아니라
            // 거래소에 실제 USDT를 들고 있는 사용자에게 실현 가능한 값이다
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
