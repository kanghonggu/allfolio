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

    // ── periodTwrPercent (대시보드·performance 공용, QA 후속 #3) ──

    @Test
    fun `periodTwrPercent - percent 스케일로 반환한다`() {
        val result = ReturnsCalculator.periodTwrPercent(
            navSeries = listOf(NavPoint(d(1), bd("1000")), NavPoint(d(30), bd("1100"))),
            flows = emptyList(),
            cutoff = d(1), asOf = d(30),
        )
        assertClose("10.00", result, eps = "0.01")
    }

    @Test
    fun `periodTwrPercent - 시계열 첫 관측이 cutoff 이후면 커버리지 미달 null`() {
        // 부분 시계열로 전체 기간 수익률을 만들어내는 왜곡(+2060%) 방지
        val result = ReturnsCalculator.periodTwrPercent(
            navSeries = listOf(NavPoint(d(10), bd("1000")), NavPoint(d(30), bd("1100"))),
            flows = emptyList(),
            cutoff = d(5), asOf = d(30),
        )
        assertNull(result)
    }

    @Test
    fun `periodTwrPercent - cutoff 이전 마지막 관측을 기저로 쓴다`() {
        val result = ReturnsCalculator.periodTwrPercent(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(3), bd("1000")),
                NavPoint(d(30), bd("1200")),
            ),
            flows = emptyList(),
            cutoff = d(5), asOf = d(30),
        )
        // 기저 = 6/3 관측(1000) → +20%
        assertClose("20.00", result, eps = "0.01")
    }

    @Test
    fun `periodTwrPercent - 관측 2건 미만이면 null`() {
        assertNull(
            ReturnsCalculator.periodTwrPercent(
                navSeries = listOf(NavPoint(d(1), bd("1000"))),
                flows = emptyList(),
                cutoff = d(1), asOf = d(30),
            )
        )
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

    // ── segmentReturns (리스크 지표용 구간 수익률) ──────────────────

    @Test
    fun `segmentReturns - 입금일은 수익률 0이고 출금일은 손실이 아니다`() {
        // 1000 → 6/2 입금 1000(NAV 2000) → 6/3 +5%(2100) → 6/4 출금 600(NAV 1500)
        val segs = ReturnsCalculator.segmentReturns(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(2), bd("2000")),
                NavPoint(d(3), bd("2100")),
                NavPoint(d(4), bd("1500")),
            ),
            flows = listOf(Flow(d(2), bd("1000")), Flow(d(4), bd("-600"))),
        )
        assertEquals(listOf(d(2), d(3), d(4)), segs.map { it.date })
        assertClose("0", segs[0].ratio)      // (2000-1000-1000)/(1000+1000)
        assertClose("0.05", segs[1].ratio)   // 100/2000
        assertClose("0", segs[2].ratio)      // (1500-2100+600)/2100
    }

    @Test
    fun `segmentReturns - 체인링킹하면 calculate의 twr과 같다`() {
        val navs = listOf(
            NavPoint(d(1), bd("1000")),
            NavPoint(d(5), bd("2050")),
            NavPoint(d(9), bd("1900")),
            NavPoint(d(20), bd("2300")),
        )
        val flows = listOf(Flow(d(5), bd("1000")), Flow(d(20), bd("-100")))
        val chained = ReturnsCalculator.segmentReturns(navs, flows)
            .fold(BigDecimal.ONE) { acc, s -> acc * (BigDecimal.ONE + s.ratio) } - BigDecimal.ONE
        val twr = ReturnsCalculator.calculate(navs, flows, d(1), d(20)).twr
        assertClose(twr!!.toPlainString(), chained, eps = "1E-12")
    }

    @Test
    fun `segmentReturns - 분모가 0 이하인 구간은 건너뛴다`() {
        // 6/2 전액 출금으로 NAV 0 → 6/3 재입금. 6/3 구간은 기저가 0이라 판단 불가
        val segs = ReturnsCalculator.segmentReturns(
            navSeries = listOf(
                NavPoint(d(1), bd("1000")),
                NavPoint(d(2), bd("0")),
                NavPoint(d(3), bd("500")),
            ),
            flows = listOf(Flow(d(2), bd("-1000"))),
        )
        assertEquals(listOf(d(2)), segs.map { it.date })
    }

    @Test
    fun `segmentReturns - 입력 순서와 무관하게 날짜순 구간을 낸다`() {
        val sorted = listOf(NavPoint(d(1), bd("1000")), NavPoint(d(2), bd("1100")), NavPoint(d(3), bd("990")))
        val segs = ReturnsCalculator.segmentReturns(sorted.reversed(), emptyList())
        assertEquals(listOf(d(2), d(3)), segs.map { it.date })
        assertClose("0.1", segs[0].ratio)
        assertClose("-0.1", segs[1].ratio)
    }
}
