package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.ReturnsCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

class NavFxAssemblyTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 6, day)

    @Test
    fun `첫 관측일의 navAtPriorFx는 null이다`() {
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000")),
            rowsByDate = mapOf(d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000")))),
        )
        assertEquals(1, result.size)
        assertNull(result[0].navAtPriorFx)
    }

    @Test
    fun `환율이 오르면 navAtPriorFx가 nav보다 작다`() {
        // USD 1단위 보유. 환율 1000 → 1100. nav는 performance_daily에서 온 값(1100).
        // navAtPriorFx = 1100 + 1*(1000 − 1100) = 1000
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000"), d(2) to bd("1100")),
            rowsByDate = mapOf(
                d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000"))),
                d(2) to listOf(CurrencyRow("USD", bd("1"), bd("1100"))),
            ),
        )
        assertEquals(2, result.size)
        assertEquals(0, bd("1000").compareTo(result[1].navAtPriorFx!!))
    }

    @Test
    fun `환율이 안 변하면 navAtPriorFx가 nav와 정확히 같다`() {
        // 이게 깨지면 환율이 안 움직인 날에도 환율 기여가 0이 아니게 된다.
        // nav를 Σv*r로 재계산하지 않고 그대로 쓰는 이유가 이것이다.
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000"), d(2) to bd("1234567")),
            rowsByDate = mapOf(
                d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000"))),
                d(2) to listOf(CurrencyRow("USD", bd("3"), bd("1000"))),
            ),
        )
        assertEquals(0, bd("1234567").compareTo(result[1].navAtPriorFx!!))
    }

    @Test
    fun `전일에 없던 통화는 당일 환율을 쓴다 - 환율 기여 0`() {
        // 신규 매수 — 전일에 보유가 없었으니 그날 환율 기여가 0인 게 맞다
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000"), d(2) to bd("3000")),
            rowsByDate = mapOf(
                d(1) to listOf(CurrencyRow("KRW", bd("1000"), BigDecimal.ONE)),
                d(2) to listOf(
                    CurrencyRow("KRW", bd("1000"), BigDecimal.ONE),
                    CurrencyRow("USD", bd("2"), bd("1000")),
                ),
            ),
        )
        assertEquals(0, bd("3000").compareTo(result[1].navAtPriorFx!!))
    }

    @Test
    fun `통화별 행이 없는 날은 빼지 않고 navAtPriorFx를 null로 둔다`() {
        // 쓰기가 실패한 날. **날짜를 빼면 안 된다** — 빼면 attribute()가 calculate()와
        // 다른 구간 집합을 돌게 되고, 입출금이 있는 계정에서만 조용히 10%p씩 틀린다.
        // null로 두면 attribute()의 기존 가드가 분해 전체를 포기시킨다.
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1000"), d(2) to bd("1100"), d(3) to bd("1200")),
            rowsByDate = mapOf(
                d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000"))),
                d(3) to listOf(CurrencyRow("USD", bd("1"), bd("1200"))),
            ),
        )
        assertEquals(3, result.size)
        assertEquals(listOf(d(1), d(2), d(3)), result.map { it.date })
        assertNull(result[1].navAtPriorFx) // 그날 통화 행이 없다
        assertNull(result[2].navAtPriorFx) // 전일 통화 행이 없다
    }

    @Test
    fun `계열 길이가 performance_daily 날짜 수와 항상 같다`() {
        // §4의 항등식은 attribute()와 calculate()가 같은 계열을 볼 때만 성립한다.
        // 이 테스트가 그 전제를 지킨다.
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(1) to bd("1"), d(2) to bd("2"), d(3) to bd("3"), d(4) to bd("4")),
            rowsByDate = emptyMap(),
        )
        assertEquals(4, result.size)
        assertTrue(result.all { it.navAtPriorFx == null })
    }

    @Test
    fun `날짜 오름차순으로 돌려준다`() {
        val result = JdbcNavFxHistorySource.assemble(
            navByDate = mapOf(d(3) to bd("1200"), d(1) to bd("1000")),
            rowsByDate = mapOf(
                d(3) to listOf(CurrencyRow("USD", bd("1"), bd("1200"))),
                d(1) to listOf(CurrencyRow("USD", bd("1"), bd("1000"))),
            ),
        )
        assertEquals(listOf(d(1), d(3)), result.map { it.date })
    }

    // --- assemble() → ReturnsCalculator 왕복: 항등식 `(1+자산)(1+환율)−1 == TWR` ---

    /**
     * nav는 performance_daily에서 온 값이라 `Σ v·r`과 정확히 일치하지 않는다(toKrw의 원 단위
     * 반올림). 그 어긋남을 일부러 넣어 둔다 — 항등식이 그것과 무관하게 성립해야 한다.
     */
    private val navByDate = mapOf(
        d(1) to bd("2300000"), // 1,000,000 + 1,300,000
        d(2) to bd("2346401"), // 1,000,000 + 1,346,400 에 반올림 오차 +1
        d(3) to bd("2802898"), // 1,500,000 + 1,302,900 에 반올림 오차 −2
        d(4) to bd("2875502"), // 1,500,000 + 1,375,500 에 반올림 오차 +2
    )

    private val rowsByDate = mapOf(
        d(1) to listOf(CurrencyRow("KRW", bd("1000000"), BigDecimal.ONE), CurrencyRow("USD", bd("1000"), bd("1300"))),
        d(2) to listOf(CurrencyRow("KRW", bd("1000000"), BigDecimal.ONE), CurrencyRow("USD", bd("1020"), bd("1320"))),
        d(3) to listOf(CurrencyRow("KRW", bd("1500000"), BigDecimal.ONE), CurrencyRow("USD", bd("1010"), bd("1290"))),
        d(4) to listOf(CurrencyRow("KRW", bd("1500000"), BigDecimal.ONE), CurrencyRow("USD", bd("1050"), bd("1310"))),
    )

    /** 3일차 50만 원 입금 — 흐름이 없으면 체인링킹이 접혀 항등식 검사가 아무것도 증명하지 못한다 */
    private val flows = listOf(Flow(d(3), bd("500000")))

    @Test
    fun `분해 결과를 다시 곱하면 TWR과 같다 - 입금 포함`() {
        val series = JdbcNavFxHistorySource.assemble(navByDate, rowsByDate)
        val navPoints = navByDate.toSortedMap().map { (date, nav) -> NavPoint(date, nav) }

        val twr = ReturnsCalculator.calculate(navPoints, flows, d(1), d(4)).twr
        val attribution = ReturnsCalculator.attribute(series, flows, d(1), d(4))
        assertNotNull(twr)
        assertNotNull(attribution)

        val recomposed = (BigDecimal.ONE + attribution!!.assetContribution)
            .multiply(BigDecimal.ONE + attribution.fxContribution, MathContext(20))
            .minus(BigDecimal.ONE)
        assertTrue(
            (recomposed - twr!!).abs() < bd("1E-12"),
            "재구성 $recomposed 와 TWR $twr 이 갈라졌다",
        )
        // 환율이 실제로 움직였으니 환율 다리가 0이 아니어야 한다 — 아니면 위 검사가 공허하다
        assertTrue(attribution.fxContribution.abs() > bd("1E-6"), "환율 기여가 0이면 검사가 무의미하다")
    }

    @Test
    fun `통화 행이 빠진 날이 있으면 분해는 null이다 - 틀린 숫자보다 낫다`() {
        val holed = rowsByDate - d(3)
        val series = JdbcNavFxHistorySource.assemble(navByDate, holed)
        val navPoints = navByDate.toSortedMap().map { (date, nav) -> NavPoint(date, nav) }

        // TWR은 여전히 나온다 — 화면 숫자는 통화 행과 무관하다
        assertNotNull(ReturnsCalculator.calculate(navPoints, flows, d(1), d(4)).twr)
        // 분해만 포기한다. 날짜를 빼고 이어붙이면 여기서 TWR과 다른 숫자가 조용히 나온다.
        assertNull(ReturnsCalculator.attribute(series, flows, d(1), d(4)))
    }
}
