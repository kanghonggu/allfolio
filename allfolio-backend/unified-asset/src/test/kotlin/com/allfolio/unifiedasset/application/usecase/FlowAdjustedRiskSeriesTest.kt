package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FlowAdjustedRiskSeriesTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 9, day)
    private fun assertZero(actual: BigDecimal, what: String) =
        assertTrue(actual.abs() < bd("1E-9")) { "$what expected 0 but was $actual" }

    @Test
    fun `입금일 NAV 급증은 변동성으로 잡히지 않는다`() {
        // 운용 수익 0, 9/3에 입금 1,000만 원. 플로우 미조정이면 그날 +100% 수익이 된다.
        val navs = listOf(
            NavPoint(d(1), bd("10000000")),
            NavPoint(d(2), bd("10000000")),
            NavPoint(d(3), bd("20000000")),
            NavPoint(d(4), bd("20000000")),
        )
        val series = FlowAdjustedRiskSeries.build(navs, listOf(Flow(d(3), bd("10000000"))))

        val last = series.last()
        assertEquals(d(4), last.date)
        assertZero(last.volatility, "volatility")
        assertZero(last.annualizedVolatility, "annualizedVolatility")
        assertZero(last.var95, "var95")
        assertZero(last.maxDrawdown, "maxDrawdown")
    }

    @Test
    fun `출금일 NAV 급감은 낙폭으로 잡히지 않는다`() {
        // 운용 수익 0, 9/3에 절반 출금. 플로우 미조정이면 MDD −50%, VaR −50%가 된다.
        val navs = listOf(
            NavPoint(d(1), bd("10000000")),
            NavPoint(d(2), bd("10000000")),
            NavPoint(d(3), bd("5000000")),
            NavPoint(d(4), bd("5000000")),
        )
        val series = FlowAdjustedRiskSeries.build(navs, listOf(Flow(d(3), bd("-5000000"))))

        assertZero(series.last().maxDrawdown, "maxDrawdown")
        assertZero(series.last().var95, "var95")
    }

    @Test
    fun `실제 운용 손실은 그대로 낙폭으로 잡힌다`() {
        // 9/3에 입금 100과 −10% 운용 손실이 같이 온 날: (990 − 1000 − 100)/(1000 + 100) = −0.1
        val navs = listOf(
            NavPoint(d(1), bd("1000")),
            NavPoint(d(2), bd("1000")),
            NavPoint(d(3), bd("990")),
        )
        val series = FlowAdjustedRiskSeries.build(navs, listOf(Flow(d(3), bd("100"))))

        assertTrue((series.last().maxDrawdown - bd("-0.1")).abs() < bd("1E-9")) {
            "maxDrawdown expected -0.1 but was ${series.last().maxDrawdown}"
        }
    }

    @Test
    fun `창은 직전 30일 — 31일 전 구간은 빠진다`() {
        // 8/1→8/2 −50% 폭락 뒤 9/2까지 무변동. 9/1 기준 창(8/3~9/1)에서는 폭락이 빠져야 한다.
        val start = LocalDate.of(2026, 8, 1)
        val navs = (0..32).map { i ->
            NavPoint(start.plusDays(i.toLong()), if (i == 0) bd("2000") else bd("1000"))
        }
        val series = FlowAdjustedRiskSeries.build(navs, emptyList()).associateBy { it.date }

        // 8/31 창은 (8/1, 8/31] — 8/2 폭락 구간 포함
        assertTrue(series.getValue(LocalDate.of(2026, 8, 31)).maxDrawdown < bd("-0.4"))
        // 9/1 창은 (8/2, 9/1] — 폭락 구간 제외
        assertZero(series.getValue(LocalDate.of(2026, 9, 1)).maxDrawdown, "maxDrawdown on 9/1")
    }

    @Test
    fun `구간 수익률이 2건 미만인 날은 내지 않는다`() {
        val navs = listOf(NavPoint(d(1), bd("1000")), NavPoint(d(2), bd("1100")))
        assertTrue(FlowAdjustedRiskSeries.build(navs, emptyList()).isEmpty())
        assertTrue(FlowAdjustedRiskSeries.build(emptyList(), emptyList()).isEmpty())
    }
}
