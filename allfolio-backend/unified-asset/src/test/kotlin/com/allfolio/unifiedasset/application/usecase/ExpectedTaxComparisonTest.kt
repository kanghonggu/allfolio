package com.allfolio.unifiedasset.application.usecase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ExpectedTaxComparisonTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun `isoOf는 국내만 KR로 매핑하고 그 외는 null`() {
        assertThat(ExpectedTaxComparison.isoOf("국내")).isEqualTo("KR")
        assertThat(ExpectedTaxComparison.isoOf("해외")).isNull()
        assertThat(ExpectedTaxComparison.isoOf("기타")).isNull()
    }

    @Test
    fun `일치하면 편차 0 flag false`() {
        val r = ExpectedTaxComparison.compare(bd("15.40"), bd("15.4"))
        assertThat(r.expectedRate).isEqualByComparingTo("15.4")
        assertThat(r.deviationPp).isEqualByComparingTo("0.00")
        assertThat(r.flagged).isFalse
    }

    @Test
    fun `0_5%p 초과면 flag true`() {
        val r = ExpectedTaxComparison.compare(bd("20.00"), bd("15.4"))
        assertThat(r.deviationPp).isEqualByComparingTo("4.60")
        assertThat(r.flagged).isTrue
    }

    @Test
    fun `0_5%p 경계 이하면 flag false`() {
        val r = ExpectedTaxComparison.compare(bd("15.80"), bd("15.4"))  // 편차 0.40
        assertThat(r.deviationPp).isEqualByComparingTo("0.40")
        assertThat(r.flagged).isFalse
        val r2 = ExpectedTaxComparison.compare(bd("15.90"), bd("15.4")) // 편차 0.50 (경계, >0.5 아님)
        assertThat(r2.deviationPp).isEqualByComparingTo("0.50")
        assertThat(r2.flagged).isFalse
    }

    @Test
    fun `기대율 null이면 대조 생략`() {
        val r = ExpectedTaxComparison.compare(bd("15.40"), null)
        assertThat(r.expectedRate).isNull()
        assertThat(r.deviationPp).isNull()
        assertThat(r.flagged).isFalse
    }
}
