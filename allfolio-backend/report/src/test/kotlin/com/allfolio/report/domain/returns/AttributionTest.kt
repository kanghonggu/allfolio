package com.allfolio.report.domain.returns

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class AttributionTest {

    private fun bd(v: String) = BigDecimal(v)
    private fun d(day: Int) = LocalDate.of(2026, 6, day)

    private fun assertClose(expected: String, actual: BigDecimal?, eps: String = "0.0001") {
        requireNotNull(actual) { "expected $expected but was null" }
        assertTrue((actual - bd(expected)).abs() < bd(eps)) { "expected $expected but was $actual" }
    }

    /** 계약 2 — 분해 합이 TWR과 일치한다. 이 설계 전체가 이 등식 하나를 위해 존재한다. */
    private fun assertMatchesTwr(series: List<NavFxPoint>, flows: List<Flow>) {
        // requireNotNull을 쓴다 — assertNotNull은 스마트캐스트를 못 걸어서
        // 뒤에서 !!를 계속 붙여야 하고, 그러다 한 곳을 빠뜨리면 컴파일이 깨진다
        val attribution = requireNotNull(
            ReturnsCalculator.attribute(series, flows, series.first().date, series.last().date)
        ) { "attribution was null" }
        val twr = requireNotNull(
            ReturnsCalculator.calculate(
                series.map { NavPoint(it.date, it.nav) }, flows, series.first().date, series.last().date,
            ).twr
        )
        val combined = (BigDecimal.ONE + attribution.assetContribution)
            .multiply(BigDecimal.ONE + attribution.fxContribution) - BigDecimal.ONE
        assertTrue((combined - twr).abs() < bd("0.000000000001")) {
            "(1+asset)(1+fx)-1 = $combined 인데 TWR = $twr"
        }
    }

    @Test
    fun `환율만 오른 구간은 자산 기여가 정확히 0이다`() {
        // 보유가 전혀 안 변하고 환율만 10% 오른 하루.
        // navAtPriorFx = 전일 환율로 평가한 당일 = 1000 (= 전일 NAV)
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1100"), navAtPriorFx = bd("1000")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
        assertClose("0", result.assetContribution)
        assertClose("0.1", result.fxContribution)
    }

    @Test
    fun `환율이 안 변하면 환율 기여가 정확히 0이다`() {
        // navAtPriorFx == nav 이면 환율이 안 움직였다는 뜻
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1200"), navAtPriorFx = bd("1200")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
        assertClose("0.2", result.assetContribution)
        assertClose("0", result.fxContribution)
    }

    @Test
    fun `자산과 환율이 같이 움직이면 곱으로 분해된다`() {
        // 자산 +20%, 환율 +10% → 원화 +32%
        // navAtPriorFx = 1200 (자산만), nav = 1320 (자산 × 환율)
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1320"), navAtPriorFx = bd("1200")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
        assertClose("0.2", result.assetContribution)
        assertClose("0.1", result.fxContribution)
        assertMatchesTwr(series, emptyList())
    }

    @Test
    fun `입출금이 있어도 분해 합이 TWR과 일치한다`() {
        // 이 테스트가 없으면 계약 2는 아무것도 증명하지 못한다 —
        // 입출금이 없으면 어떤 엉성한 분해도 우연히 맞을 수 있다.
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(10), nav = bd("2100"), navAtPriorFx = bd("2000")),  // 6/5 입금 1000
            NavFxPoint(d(20), nav = bd("2500"), navAtPriorFx = bd("2400")),
            NavFxPoint(d(30), nav = bd("1600"), navAtPriorFx = bd("1650")),  // 6/25 출금 500
        )
        val flows = listOf(Flow(d(5), bd("1000")), Flow(d(25), bd("-500")))
        assertMatchesTwr(series, flows)
    }

    @Test
    fun `분모가 0 이하인 구간을 twr과 똑같이 건너뛴다`() {
        // 전액 출금(6/10) → 빈 채로 유지(6/20) → 재입금(6/25) → 성장(6/30).
        //
        // 6/20 구간의 분모는 `전일 NAV(0) + 입금(0) = 0`이라 twr()이 통째로 건너뛴다.
        // attribute()가 같이 안 건너뛰면 0으로 나누게 되고, 건너뛰는 구간이 갈라지면
        // 항등식이 깨진다.
        //
        // 출금을 관측일과 같은 날에 둔 것이 중요하다 — 출금 없이 NAV만 0이 되면
        // r = −1이 되어 자산 다리가 −100%에 붙고, 그건 다른 규칙(null 반환)에 걸린다.
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(10), nav = bd("0"), navAtPriorFx = bd("0")),
            NavFxPoint(d(20), nav = bd("0"), navAtPriorFx = bd("0")),
            NavFxPoint(d(30), nav = bd("600"), navAtPriorFx = bd("590")),
        )
        val flows = listOf(Flow(d(10), bd("-1000")), Flow(d(25), bd("500")))
        assertMatchesTwr(series, flows)

        // 건너뛴 구간이 실제로 있었는지 확인 — 없으면 이 테스트가 아무것도 안 지킨다
        val result = requireNotNull(ReturnsCalculator.attribute(series, flows, d(1), d(30)))
        assertClose("0.18", result.assetContribution)
    }

    @Test
    fun `관측이 2건 미만이면 null`() {
        val series = listOf(NavFxPoint(d(1), bd("1000"), null))
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(30)))
    }

    @Test
    fun `navAtPriorFx가 없는 구간이 있으면 null`() {
        // 통화별 행이 그날 안 써진 경우 — 억지로 이으면 환율 차이가 0으로 잡혀
        // 자산 쪽에 조용히 흡수된다
        val series = listOf(
            NavFxPoint(d(1), bd("1000"), null),
            NavFxPoint(d(2), bd("1100"), null),
        )
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
    }

    @Test
    fun `자산 다리가 마이너스 100퍼센트에 근접하면 null이다`() {
        // r_fx가 발산한다 — 억지 숫자를 내는 대신 분해를 포기한다
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1"), navAtPriorFx = bd("0")),
        )
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
    }

    // 아래 네 건은 임계값 자체를 고정한다. 위의 `근접` 테스트는 1+r_asset이 **정확히 0**인
    // 입력이라, 가드를 `signum() == 0`으로 바꿔도 통과한다 — 임계값의 크기도 부호 처리도
    // 아무것도 안 지키고 있었다.
    //
    // 값을 정확히 놓을 수 있는 근거: 플로우가 없으면 분모 = 전일 NAV, 출금 = 0이므로
    // `1 + r_asset = navAtPriorFx / 전일 NAV`.

    @Test
    fun `자산 다리가 작아도 임계값 위면 분해한다`() {
        // 1 + r_asset = 2 / 1_000_000 = 2E-6 — 임계값(1E-6) 바로 위.
        // 가드가 과하게 커지면(예: 1E-5) 이 테스트가 먼저 깨진다.
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("3"), navAtPriorFx = bd("2")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
        assertClose("-0.999998", result.assetContribution)
        assertClose("0.5", result.fxContribution)
        assertMatchesTwr(series, emptyList())
    }

    @Test
    fun `자산 다리가 임계값과 정확히 같으면 null이다`() {
        // 1 + r_asset = 1 / 1_000_000 = 1E-6. 경계는 닫혀 있어야 한다 —
        // `< 임계값`이면 이 값이 통과해 버린다.
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("2"), navAtPriorFx = bd("1")),
        )
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
    }

    @Test
    fun `자산 다리가 원화 정수 한 칸이면 null이다`() {
        // 10억 원 기저에서 navAtPriorFx가 1원 — NAV가 원 단위 정수라 이게 0 아닌 최소값이고,
        // 그 값이 하필 정확히 1E-9이다. 임계값을 1E-9에 두면 격자 위에 앉아 통과하고
        // 환율 다리가 +99,999,900%로 나온다.
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000000000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1000000"), navAtPriorFx = bd("1")),
        )
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
    }

    @Test
    fun `navAtPriorFx가 음수면 null이다`() {
        // 1 + r_asset = (frozen − 출금)/분모 이고 분모 > 0, 출금 ≤ 0이므로
        // 이 값이 음수인 경우는 navAtPriorFx < 0뿐 — 정상 데이터가 아니라 상류 부호 오류다.
        // abs()로 재던 시절엔 −5가 임계값을 여유롭게 통과해 환율 −120.2%를 뱉었다.
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000000"), navAtPriorFx = null),
            NavFxPoint(d(2), nav = bd("1010000"), navAtPriorFx = bd("-5000000")),
        )
        assertNull(ReturnsCalculator.attribute(series, emptyList(), d(1), d(2)))
    }

    @Test
    fun `기간 밖 관측은 제외한다`() {
        val series = listOf(
            NavFxPoint(d(1), bd("9999"), null),
            NavFxPoint(d(10), nav = bd("1000"), navAtPriorFx = bd("1000")),
            NavFxPoint(d(20), nav = bd("1320"), navAtPriorFx = bd("1200")),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(10), d(20)))
        assertClose("0.2", result.assetContribution)
        assertClose("0.1", result.fxContribution)
    }

    @Test
    fun `정렬되지 않은 입력도 날짜 순으로 처리한다`() {
        val series = listOf(
            NavFxPoint(d(20), nav = bd("1320"), navAtPriorFx = bd("1200")),
            NavFxPoint(d(10), nav = bd("1000"), navAtPriorFx = null),
        )
        val result = requireNotNull(ReturnsCalculator.attribute(series, emptyList(), d(10), d(20)))
        assertClose("0.2", result.assetContribution)
    }

    @Test
    fun `attribute의 원화 다리는 twr과 같은 값을 낸다`() {
        // 구간 규약이 갈라지는 순간 잡힌다
        val series = listOf(
            NavFxPoint(d(1), nav = bd("1000"), navAtPriorFx = null),
            NavFxPoint(d(15), nav = bd("2000"), navAtPriorFx = bd("1950")),
            NavFxPoint(d(30), nav = bd("2200"), navAtPriorFx = bd("2210")),
        )
        val flows = listOf(Flow(d(15), bd("1000")))
        assertMatchesTwr(series, flows)
        val twr = ReturnsCalculator.calculate(
            series.map { NavPoint(it.date, it.nav) }, flows, d(1), d(30),
        ).twr
        assertEquals(0, bd("0.1").compareTo(twr!!.setScale(1, java.math.RoundingMode.HALF_UP)))
    }
}
