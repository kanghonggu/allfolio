package com.allfolio.unifiedasset.application.usecase

import java.math.BigDecimal
import java.math.RoundingMode

data class TaxComparison(val expectedRate: BigDecimal?, val deviationPp: BigDecimal?, val flagged: Boolean)

/** 배당 실효 원천징수율 vs 기준(기대) 세율 대조(사실형 정합 체크). 조언 아님. */
object ExpectedTaxComparison {
    private val THRESHOLD_PP = BigDecimal("0.5")

    /** byCountry 라벨 → ISO2. "국내"만 KR로 신뢰 매핑, 그 외는 국가 판별 불가 → null. */
    fun isoOf(countryLabel: String): String? = if (countryLabel == "국내") "KR" else null

    /** 실효세율 vs 기대율(둘 다 % 스케일). 기대율 null이면 대조 생략. */
    fun compare(actualEffRate: BigDecimal, expectedRate: BigDecimal?): TaxComparison {
        if (expectedRate == null) return TaxComparison(null, null, false)
        val dev = actualEffRate.subtract(expectedRate).setScale(2, RoundingMode.HALF_UP)
        return TaxComparison(expectedRate, dev, dev.abs() > THRESHOLD_PP)
    }
}
