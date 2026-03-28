package com.allfolio.risk.domain

import java.math.BigDecimal

/**
 * 특정 기간의 포트폴리오 리스크 계산 결과.
 * 불변 객체 — 생성 후 수정 불가.
 *
 * volatility            : 일간 수익률 표준편차 (raw)
 * annualizedVolatility  : 연환산 변동성 = σ × √252
 * var95                 : Historical VaR 95% (하위 5% 수익률, 음수 = 손실)
 * maxDrawdown           : 최대 낙폭 (MDD, 음수 표현)
 */
data class RiskSnapshot(
    val volatility: BigDecimal,
    val annualizedVolatility: BigDecimal,
    val var95: BigDecimal,
    val maxDrawdown: BigDecimal,
) {
    fun isHighRisk(volThreshold: BigDecimal): Boolean =
        annualizedVolatility > volThreshold

    companion object {
        fun empty(): RiskSnapshot = RiskSnapshot(
            volatility           = BigDecimal.ZERO,
            annualizedVolatility = BigDecimal.ZERO,
            var95                = BigDecimal.ZERO,
            maxDrawdown          = BigDecimal.ZERO,
        )
    }
}
