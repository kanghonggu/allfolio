package com.allfolio.report.domain.returns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * TWR/MWR 계산의 **가장자리** (AF-174).
 *
 * ## 기존 테스트가 안 무는 곳만 골랐다
 *
 * [ReturnsCalculatorTest]가 정상 성장·입출금·커버리지 미달을, [AttributionTest]가 분해
 * 항등식과 분모 0 건너뜀을 이미 문다. **중복해서 또 쓰지 않았다.**
 *
 * 남아 있던 자리는 셋이다:
 *
 * 1. **MWR을 못 푸는 경우** — XIRR은 수치해라 항상 답이 나오지 않는다. 못 풀었을 때
 *    0이나 예외가 아니라 **null**이어야 하고, 그동안 TWR은 정상적으로 나와야 한다
 * 2. **기간이 0일** — 같은 날 두 관측. `days <= 0`이면 연율 환산이 성립하지 않는다
 * 3. **플로우 창의 양 끝** — `calculate`가 쓰는 창은 `(첫 관측일, 마지막 관측일]`이다.
 *    **기초 관측일 당일 입금은 제외**된다 — 그 돈은 이미 기초 NAV 안에 들어 있기 때문이다.
 *    이 경계가 한 칸 밀리면 순유입이 두 번 세어져 손익이 통째로 뒤집힌다
 */
class ReturnsCalculatorEdgeTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 6, day)
    private fun assertClose(expected: String, actual: BigDecimal?, eps: String = "0.0001") {
        requireNotNull(actual) { "expected $expected but was null" }
        assertTrue((actual - bd(expected)).abs() < bd(eps)) { "expected $expected but was $actual" }
    }

    /**
     * 계좌를 전부 청산해 기말 NAV가 0인 경우.
     *
     * TWR은 −100%로 정확히 나온다. 반면 XIRR의 순현재가치는 **부호가 안 바뀌어** 근이 없다
     * (현금흐름이 `−기초NAV`와 `+0`뿐이다). 뉴턴은 도함수가 0이라, 이분법은 두 끝의 부호가
     * 같아서 각각 포기한다.
     *
     * 🔴 **그때 0을 돌려주면 "본전"으로 읽힌다.** 전액을 잃은 계좌가 수익률 0%로 보이는
     * 것이라, null(=산출 불가)과 0(=변동 없음)의 구분이 여기서 값을 가진다.
     */
    @Test
    fun `전량 청산으로 MWR을 못 풀면 null이고 TWR은 그대로 나온다`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(1), bd("1000")), NavPoint(d(30), BigDecimal.ZERO)),
            flows = emptyList(),
            from = d(1), to = d(30),
        )

        assertClose("-1", result.twr)
        assertNull(result.mwr, "못 푼 XIRR을 0으로 돌려주면 전액 손실이 본전으로 보인다")
        assertClose("-1000", result.investmentPnl)
    }

    /**
     * 관측이 둘인데 같은 날이면 기간이 0일이다. MWR은 null이고 TWR은 구간 수익률이라 그대로
     * 계산된다.
     *
     * 🔴 **이 테스트는 `days <= 0` 가드 그 줄을 무는 게 아니다.** 변이로 확인했다(2026-09-10):
     * 그 줄을 지워도 통과한다. 기간이 0일이면 모든 현금흐름의 할인 지수가 0이라 순현재가치가
     * **상수**가 되고, 뉴턴은 도함수 0으로, 이분법은 부호가 안 바뀌어 각각 포기하기 때문이다.
     * 가드는 같은 결론에 이르는 **두 번째 자물쇠**다.
     *
     * 그래도 남겨 두는 이유는 여기서 무는 것이 구현이 아니라 **계약**이기 때문이다 —
     * 0일 구간에 MWR을 지어내지 않는다. 솔버를 갈아 끼워도 이 단언은 그대로여야 한다.
     */
    @Test
    fun `같은 날 두 관측이면 MWR은 null이다`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(10), bd("1000")), NavPoint(d(10), bd("1100"))),
            flows = emptyList(),
            from = d(1), to = d(30),
        )

        assertNotNull(result.twr, "구간 수익률은 기간 길이와 무관하다")
        assertClose("0.1", result.twr)
        assertNull(result.mwr, "0일을 연율로 환산할 수 없다")
    }

    /**
     * 🔴 **기초 관측일 당일 입금은 플로우가 아니다.**
     *
     * 그날의 NAV가 이미 그 입금을 반영한 값이라, 플로우로도 세면 같은 돈을 두 번 센다.
     * 창이 `[첫날, 마지막날]`로 한 칸 넓어지면 아래 손익이 +200에서 −800으로 뒤집힌다.
     */
    @Test
    fun `기초 관측일 당일 플로우는 제외한다`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(1), bd("2000")), NavPoint(d(30), bd("2200"))),
            flows = listOf(Flow(d(1), bd("1000"))),
            from = d(1), to = d(30),
        )

        assertEquals(0, BigDecimal.ZERO.compareTo(result.netFlow), "기초일 입금은 이미 기초 NAV 안에 있다")
        assertClose("200", result.investmentPnl)
        assertClose("0.1", result.twr)
    }

    /**
     * 반대쪽 끝은 **포함**이다. 마지막 관측일에 들어온 돈은 그날 NAV에 반영돼 있으므로
     * 빼 주지 않으면 그 입금이 수익으로 잡힌다.
     */
    @Test
    fun `기말 관측일 당일 플로우는 포함한다`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(1), bd("1000")), NavPoint(d(30), bd("1600"))),
            flows = listOf(Flow(d(30), bd("500"))),
            from = d(1), to = d(30),
        )

        assertClose("500", result.netFlow)
        assertClose("100", result.investmentPnl, eps = "0.0001")
        // r = (1600 − 1000 − 500) / (1000 + 500) = 100/1500
        assertClose("0.066667", result.twr, eps = "0.0001")
    }

    /**
     * 🔴 커버리지 앵커는 **cutoff 이전 마지막** 관측이다 (AF-199 · 변이 RRG-M3).
     *
     * 기존 `ReturnsCalculatorTest > periodTwrPercent - cutoff 이전 마지막 관측을 기저로 쓴다`가
     * 이름으로는 이걸 문다. 그런데 **두 후보 관측의 NAV가 둘 다 1000**이라 앵커를 앞으로
     * 옮겨도 답이 같다 — 기준선에서 `sorted.last` → `sorted.first` 변이가 통과한 이유다.
     *
     * 여기서는 두 후보의 NAV를 다르게 둬서 **선택이 결과를 바꾸게** 한다:
     *
     * | 앵커 | 기저 | 결과 |
     * |---|---|---|
     * | 6/3 (마지막, 올바름) | 1000 | +20% |
     * | 6/1 (첫, 변이) | 800 | +50% |
     */
    @Test
    fun `앵커는 cutoff 이전 마지막 관측이다 — 첫 관측이 아니다`() {
        val result = ReturnsCalculator.periodTwrPercent(
            navSeries = listOf(
                NavPoint(d(1), bd("800")),
                NavPoint(d(3), bd("1000")),
                NavPoint(d(30), bd("1200")),
            ),
            flows = emptyList(),
            cutoff = d(5), asOf = d(30),
        )

        assertClose("20.00", result, eps = "0.01")
    }

    /**
     * 기간 **밖**의 플로우는 애초에 안 들어온다 — `from..to` 필터가 먼저 걸린다.
     * 위 두 경계와 달리 이건 `periodFlows` 단계라 자리가 다르고, 한쪽만 고쳐도 다른 쪽은
     * 조용히 남는다.
     */
    @Test
    fun `기간 밖 플로우는 순유입에 안 들어간다`() {
        val result = ReturnsCalculator.calculate(
            navSeries = listOf(NavPoint(d(10), bd("1000")), NavPoint(d(20), bd("1100"))),
            flows = listOf(Flow(d(5), bd("9999")), Flow(d(25), bd("8888"))),
            from = d(10), to = d(20),
        )

        assertEquals(0, BigDecimal.ZERO.compareTo(result.netFlow))
        assertClose("100", result.investmentPnl)
    }
}
