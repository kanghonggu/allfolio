package com.allfolio.dashboard

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MetricsCalculatorTest {

    // ── returnToGrade ─────────────────────────────────────────

    @Test
    fun `수익률 15% 이상 - EXCELLENT`() {
        assertEquals(MetricGrade.EXCELLENT, MetricsCalculator.returnToGrade(bd("15")))
        assertEquals(MetricGrade.EXCELLENT, MetricsCalculator.returnToGrade(bd("30")))
    }

    @Test
    fun `수익률 5~15% - GOOD`() {
        assertEquals(MetricGrade.GOOD, MetricsCalculator.returnToGrade(bd("5")))
        assertEquals(MetricGrade.GOOD, MetricsCalculator.returnToGrade(bd("14.99")))
    }

    @Test
    fun `수익률 0~5% - WARN`() {
        assertEquals(MetricGrade.WARN, MetricsCalculator.returnToGrade(bd("0")))
        assertEquals(MetricGrade.WARN, MetricsCalculator.returnToGrade(bd("4.99")))
    }

    @Test
    fun `수익률 음수 - BAD`() {
        assertEquals(MetricGrade.BAD, MetricsCalculator.returnToGrade(bd("-1")))
        assertEquals(MetricGrade.BAD, MetricsCalculator.returnToGrade(bd("-50")))
    }

    // ── returnToStars ─────────────────────────────────────────

    @Test
    fun `별점 - 20% 이상 5성`() = assertEquals(5, MetricsCalculator.returnToStars(bd("20")))
    @Test
    fun `별점 - 10~20% 4성`() = assertEquals(4, MetricsCalculator.returnToStars(bd("10")))
    @Test
    fun `별점 - 3~10% 3성`() = assertEquals(3, MetricsCalculator.returnToStars(bd("3")))
    @Test
    fun `별점 - 0~3% 2성`() = assertEquals(2, MetricsCalculator.returnToStars(bd("0")))
    @Test
    fun `별점 - 음수 1성`() = assertEquals(1, MetricsCalculator.returnToStars(bd("-5")))

    // ── mddToGrade ────────────────────────────────────────────

    @Test
    fun `MDD -5% 이상 - EXCELLENT`() {
        assertEquals(MetricGrade.EXCELLENT, MetricsCalculator.mddToGrade(bd("-4")))
        assertEquals(MetricGrade.EXCELLENT, MetricsCalculator.mddToGrade(bd("0")))
    }

    @Test
    fun `MDD -15~-5% - GOOD`() {
        assertEquals(MetricGrade.GOOD, MetricsCalculator.mddToGrade(bd("-5.01")))
        assertEquals(MetricGrade.GOOD, MetricsCalculator.mddToGrade(bd("-15")))
    }

    @Test
    fun `MDD -30~-15% - WARN`() {
        assertEquals(MetricGrade.WARN, MetricsCalculator.mddToGrade(bd("-15.01")))
        assertEquals(MetricGrade.WARN, MetricsCalculator.mddToGrade(bd("-30")))
    }

    @Test
    fun `MDD -30% 미만 - BAD`() {
        assertEquals(MetricGrade.BAD, MetricsCalculator.mddToGrade(bd("-30.01")))
        assertEquals(MetricGrade.BAD, MetricsCalculator.mddToGrade(bd("-60")))
    }

    // ── sharpeToGrade ─────────────────────────────────────────

    @Test
    fun `샤프 2점 이상 - EXCELLENT`() = assertEquals(MetricGrade.EXCELLENT, MetricsCalculator.sharpeToGrade(bd("2.0")))
    @Test
    fun `샤프 1~2 - GOOD`() = assertEquals(MetricGrade.GOOD, MetricsCalculator.sharpeToGrade(bd("1.0")))
    @Test
    fun `샤프 0~1 - WARN`() = assertEquals(MetricGrade.WARN, MetricsCalculator.sharpeToGrade(bd("0.5")))
    @Test
    fun `샤프 음수 - BAD`() = assertEquals(MetricGrade.BAD, MetricsCalculator.sharpeToGrade(bd("-0.1")))

    // ── concentrationToGrade ──────────────────────────────────

    @Test
    fun `집중도 30% 이하 - EXCELLENT`() {
        assertEquals(MetricGrade.EXCELLENT, MetricsCalculator.concentrationToGrade(bd("0.30")))
        assertEquals(MetricGrade.EXCELLENT, MetricsCalculator.concentrationToGrade(bd("0.10")))
    }

    @Test
    fun `집중도 30~50% - GOOD`() {
        assertEquals(MetricGrade.GOOD, MetricsCalculator.concentrationToGrade(bd("0.31")))
        assertEquals(MetricGrade.GOOD, MetricsCalculator.concentrationToGrade(bd("0.50")))
    }

    @Test
    fun `집중도 50~70% - WARN`() {
        assertEquals(MetricGrade.WARN, MetricsCalculator.concentrationToGrade(bd("0.51")))
    }

    @Test
    fun `집중도 70% 초과 - BAD`() {
        assertEquals(MetricGrade.BAD, MetricsCalculator.concentrationToGrade(bd("0.71")))
    }

    // ── volatilityToGrade ─────────────────────────────────────

    @Test
    fun `변동성 10% 이하 - EXCELLENT`() = assertEquals(MetricGrade.EXCELLENT, MetricsCalculator.volatilityToGrade(bd("10")))
    @Test
    fun `변동성 10~20% - GOOD`() = assertEquals(MetricGrade.GOOD, MetricsCalculator.volatilityToGrade(bd("15")))
    @Test
    fun `변동성 20~40% - WARN`() = assertEquals(MetricGrade.WARN, MetricsCalculator.volatilityToGrade(bd("30")))
    @Test
    fun `변동성 40% 초과 - BAD`() = assertEquals(MetricGrade.BAD, MetricsCalculator.volatilityToGrade(bd("45")))

    // ── dataWarning ───────────────────────────────────────────

    @Test
    fun `데이터 30일 이상이면 경고 없음`() {
        assertNull(MetricsCalculator.dataWarning(30))
        assertNull(MetricsCalculator.dataWarning(365))
    }

    @Test
    fun `데이터 30일 미만이면 경고 문자열 반환`() {
        val warning = MetricsCalculator.dataWarning(10)
        assertNotNull(warning)
        assertTrue(warning!!.contains("10"))
    }

    // ── weightOf ──────────────────────────────────────────────

    @Test
    fun `weightOf - total 0이면 0 반환`() {
        assertEquals(BigDecimal.ZERO, MetricsCalculator.weightOf(bd("100"), bd("0")))
    }

    @Test
    fun `weightOf - 절반이면 0_5`() {
        assertEquals(0, bd("0.5000").compareTo(MetricsCalculator.weightOf(bd("500"), bd("1000"))))
    }

    // ── pctDiff ───────────────────────────────────────────────

    @Test
    fun `pctDiff - 알파 계산`() {
        val alpha = MetricsCalculator.pctDiff(bd("20"), bd("10"))
        assertEquals(0, bd("10").compareTo(alpha))
    }

    @Test
    fun `pctDiff - 음수 알파`() {
        val alpha = MetricsCalculator.pctDiff(bd("5"), bd("15"))
        assertEquals(0, bd("-10").compareTo(alpha))
    }

    private fun bd(s: String) = BigDecimal(s)
}
