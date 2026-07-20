package com.allfolio.report.domain.returns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReturnsCalculatorTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 6, day)
    private fun assertClose(expected: String, actual: BigDecimal?, eps: String = "0.0001") {
        requireNotNull(actual) { "expected $expected but was null" }
        assertTrue((actual - bd(expected)).abs() < bd(eps)) { "expected $expected but was $actual" }
    }

    @Test
    fun `simple growth without flows`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(1), bd("1000")), NavPoint(d(30), bd("1100"))),
            flows = emptyList(),
            from = d(1), to = d(30),
        )
        assertClose("0.1", result.twr)
        assertClose("0.1", result.mwr, eps = "0.001")
        assertClose("100", result.investmentPnl)
        assertEquals(0, BigDecimal.ZERO.compareTo(result.netFlow))
    }

    @Test
    fun `deposit is not counted as return in TWR`() {
        // 1000 → (6/15 입금 1000, 당일 NAV 2000 관측) → 6/30 NAV 2200 (전액 +10% 성장)
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(15), bd("2000")),
                NavPoint(d(30), bd("2200")),
            ),
            flows = listOf(Flow(d(15), bd("1000"))),
            from = d(1), to = d(30),
        )
        // 구간1: (2000-1000-1000)/(1000+1000)=0, 구간2: 200/2000=0.1 → TWR=0.1
        assertClose("0.1", result.twr)
        assertClose("1000", result.netFlow)
        assertClose("200", result.investmentPnl)   // 2200-1000-1000
    }

    @Test
    fun `withdrawal adjusts TWR upward not downward`() {
        // 1000 → 6/15 출금 500 (당일 NAV 550 관측: 500 출금 후 +10%) → 6/30 NAV 605
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(15), bd("550")),
                NavPoint(d(30), bd("605")),
            ),
            flows = listOf(Flow(d(15), bd("-500"))),
            from = d(1), to = d(30),
        )
        // 구간1: (550-1000+500)/1000=0.05, 구간2: 55/550=0.1 → 1.05*1.1-1=0.155
        assertClose("0.155", result.twr)
        assertClose("-500", result.netFlow)
    }

    @Test
    fun `mwr reflects deposit timing while twr does not`() {
        // 큰 입금 직후 하락: TWR(시간가중)은 완만, MWR(금액가중)은 더 나쁨
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(2), bd("1100")),     // +10%
                NavPoint(d(3), bd("11100")),    // 입금 10000 반영
                NavPoint(d(30), bd("9990")),    // -10%
            ),
            flows = listOf(Flow(d(3), bd("10000"))),
            from = d(1), to = d(30),
        )
        requireNotNull(result.twr); requireNotNull(result.mwr)
        assertTrue(result.mwr!! < result.twr!!) { "mwr=${result.mwr} should be worse than twr=${result.twr}" }
    }

    @Test
    fun `single observation returns nulls`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(1), bd("1000"))),
            flows = emptyList(),
            from = d(1), to = d(30),
        )
        assertNull(result.twr)
        assertNull(result.mwr)
    }

    @Test
    fun `xirr converges to known answer`() {
        // 1년 정확히: 1000 → 1100, 플로우 없음 → 연율=기간수익률=10%
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(LocalDate.of(2025, 6, 30), bd("1000")),
                NavPoint(LocalDate.of(2026, 6, 30), bd("1100")),
            ),
            flows = emptyList(),
            from = LocalDate.of(2025, 6, 30), to = LocalDate.of(2026, 6, 30),
        )
        assertClose("0.1", result.mwr, eps = "0.001")
    }

    @Test
    fun `decomposition identity holds`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(15), bd("2000")),
                NavPoint(d(30), bd("2200")),
            ),
            flows = listOf(Flow(d(15), bd("1000"))),
            from = d(1), to = d(30),
        )
        // endNav = startNav + netFlow + investmentPnl
        assertClose("2200", result.startNav!! + result.netFlow + result.investmentPnl!!)
    }
}
