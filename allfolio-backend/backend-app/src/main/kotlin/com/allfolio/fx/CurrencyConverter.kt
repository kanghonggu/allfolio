package com.allfolio.fx

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 통화 변환 유틸리티
 *
 * KRW 기준 통합 — 모든 가격을 KRW 원화 기준으로 환산.
 * 지원 통화: KRW (1:1), USDT (Redis 캐시 환율 적용)
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
            "USDT" -> {
                val rate = fxRateService.getUsdtToKrw()
                (amount * rate).setScale(0, RoundingMode.HALF_UP)
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
