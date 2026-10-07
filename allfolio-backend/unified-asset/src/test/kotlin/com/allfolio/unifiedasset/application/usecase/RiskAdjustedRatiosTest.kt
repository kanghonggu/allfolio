package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 기대값은 구현을 부르지 않고 이 파일 안에서 double로 따로 계산한다 — 구현을 그대로 베끼면
 * 공식이 틀려도 같이 틀린다.
 */
class RiskAdjustedRatiosTest {

    private val day0 = LocalDate.of(2026, 8, 1)
    private fun bd(v: String) = BigDecimal(v)

    /** +1.0%, −0.5% 교대 — 변동성 > 0, MDD < 0 */
    private fun alternating(n: Int) = List(n) { i -> if (i % 2 == 0) 0.01 else -0.005 }

    /**
     * 구간 수익률 [returns]가 정확히 나오도록 NAV를 만든다. [deposits]는 (인덱스, 금액).
     * 입금일 NAV = (전일 NAV + 입금) × (1 + r) — TWR 구간식 분모 `NAV_{t−1} + 입금`과 같은 규약.
     */
    private fun build(returns: List<Double>, deposits: Map<Int, Double> = emptyMap()): Pair<List<NavPoint>, List<Flow>> {
        var nav = 10_000_000.0
        val navs = mutableListOf(NavPoint(day0, nav.toBigDecimal()))
        val flows = mutableListOf<Flow>()
        returns.forEachIndexed { i, r ->
            val date = day0.plusDays(i + 1L)
            val dep = deposits[i + 1] ?: 0.0
            if (dep != 0.0) flows += Flow(date, dep.toBigDecimal())
            nav = (nav + dep) * (1 + r)
            navs += NavPoint(date, nav.toBigDecimal())
        }
        return navs to flows
    }

    private data class Expected(val ann: Double, val vol: Double, val mdd: Double)

    private fun expected(returns: List<Double>, days: Long): Expected {
        val growth = returns.fold(1.0) { acc, r -> acc * (1 + r) }
        val ann = growth.pow(365.0 / days) - 1
        val mean = returns.average()
        val vol = sqrt(returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)) * sqrt(252.0)
        var cum = 1.0; var peak = 1.0; var mdd = 0.0
        for (r in returns) { cum *= 1 + r; peak = maxOf(peak, cum); mdd = minOf(mdd, (cum - peak) / peak) }
        return Expected(ann, vol, mdd)
    }

    private fun assertClose(expected: Double, actual: BigDecimal?, what: String, tol: Double = 1e-4) {
        assertNotNull(actual) { "$what is null" }
        assertTrue(kotlin.math.abs(expected - actual!!.toDouble()) < tol) { "$what expected $expected but was $actual" }
    }

    @Test
    fun `설정 이후 연환산 수익률로 샤프와 칼마를 계산한다`() {
        val returns = alternating(40)
        val (navs, flows) = build(returns)
        val e = expected(returns, days = 40)

        val r = RiskAdjustedRatios.compute(navs, flows, riskFreeRatePct = bd("3.0"))!!

        assertEquals(40, r.segments)
        assertClose(e.ann, r.annualizedReturn, "annualizedReturn", 1e-9)
        assertClose(e.vol, r.annualizedVolatility, "annualizedVolatility", 1e-9)
        assertClose(e.mdd, r.maxDrawdown, "maxDrawdown", 1e-9)
        assertClose((e.ann - 0.03) / e.vol, r.sharpe, "sharpe")
        assertClose(e.ann / -e.mdd, r.calmar, "calmar")
        // 같은 숫자가 0이면 위 비교는 아무것도 못 가린다
        assertTrue(r.sharpe!!.signum() != 0 && r.calmar!!.signum() != 0)
    }

    @Test
    fun `무위험 수익률이 바뀌면 샤프가 그만큼 바뀐다`() {
        val (navs, flows) = build(alternating(40))
        val lo = RiskAdjustedRatios.compute(navs, flows, bd("2.0"))!!
        val hi = RiskAdjustedRatios.compute(navs, flows, bd("4.0"))!!

        // Δ샤프 = −Δrf / σ — 단위(%→ratio) 환산이 틀리면 100배로 갈린다
        assertClose(-0.02 / lo.annualizedVolatility.toDouble(), hi.sharpe!! - lo.sharpe!!, "Δsharpe")
    }

    @Test
    fun `입금은 수익이 아니다 — 같은 운용 수익률이면 입금이 있어도 비율이 같다`() {
        val returns = alternating(40)
        val (plainNavs, plainFlows) = build(returns)
        // 20일째 1,000만 원 입금 — NAV가 두 배 가까이 뛴다. daily_return 기반이면 그날 +100%
        val (navs, flows) = build(returns, deposits = mapOf(20 to 10_000_000.0))

        val plain = RiskAdjustedRatios.compute(plainNavs, plainFlows, bd("3.0"))!!
        val withDeposit = RiskAdjustedRatios.compute(navs, flows, bd("3.0"))!!

        assertClose(plain.annualizedReturn.toDouble(), withDeposit.annualizedReturn, "annualizedReturn", 1e-9)
        assertClose(plain.sharpe!!.toDouble(), withDeposit.sharpe, "sharpe")
        assertClose(plain.calmar!!.toDouble(), withDeposit.calmar, "calmar")
    }

    @Test
    fun `구간 수익률이 30건 미만이면 null`() {
        val (navs29, flows29) = build(alternating(29))
        val (navs30, flows30) = build(alternating(30))

        assertNull(RiskAdjustedRatios.compute(navs29, flows29, bd("3.0")))
        assertNotNull(RiskAdjustedRatios.compute(navs30, flows30, bd("3.0")))
    }

    @Test
    fun `낙폭이 없으면 칼마는 null, 샤프는 낸다`() {
        // 매일 +0.5% / +1.0% 교대 — 하락이 한 번도 없다
        val returns = List(40) { i -> if (i % 2 == 0) 0.005 else 0.01 }
        val (navs, flows) = build(returns)

        val r = RiskAdjustedRatios.compute(navs, flows, bd("3.0"))!!

        assertEquals(0, r.maxDrawdown.signum())
        assertNull(r.calmar)
        assertNotNull(r.sharpe)
    }

    @Test
    fun `무위험 수익률이 없으면 샤프만 null`() {
        val (navs, flows) = build(alternating(40))

        val r = RiskAdjustedRatios.compute(navs, flows, riskFreeRatePct = null)!!

        assertNull(r.sharpe)
        assertNotNull(r.calmar)
    }

    @Test
    fun `연동 전 NAV 0 행은 기산일에 넣지 않는다`() {
        // 60일간 NAV 0으로 깔려 있다가 연동일에 1,000만 원이 들어온다.
        // 0 행부터 세면 기간이 100일이 되어 연환산 수익률이 크게 줄어든다.
        val returns = alternating(40)
        val (navs, flows) = build(returns)
        val zeros = (60 downTo 1).map { NavPoint(day0.minusDays(it.toLong()), BigDecimal.ZERO) }
        val linkDeposit = Flow(day0, bd("10000000"))

        val r = RiskAdjustedRatios.compute(zeros + navs, flows + linkDeposit, bd("3.0"))!!

        // 연동일 구간(0 → 1,000만 원, 입금 1,000만 원)은 수익 0인 구간이라 수익률엔 영향이 없다
        assertClose(expected(listOf(0.0) + returns, days = 41).ann, r.annualizedReturn, "annualizedReturn", 1e-9)
    }
}
