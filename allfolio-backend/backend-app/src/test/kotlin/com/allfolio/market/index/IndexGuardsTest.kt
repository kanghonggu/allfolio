package com.allfolio.market.index

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class IndexGuardsTest {

    private val guards = IndexGuards()

    /** 2026-08-12 운영 실측 (KOSPI) */
    private fun realQuote(
        price: String = "6579.04",
        change: String = "233.51",
        rate: String = "3.68",
    ) = IndexQuote(
        indexCode = "KOSPI",
        price = BigDecimal(price),
        prevClose = BigDecimal(price).subtract(BigDecimal(change)),
        change = BigDecimal(change),
        changeRate = BigDecimal(rate),
    )

    @Test
    fun `실측 값은 통과한다`() {
        assertThat(guards.check(realQuote())).isEmpty()
    }

    // 지수는 하루 몇 %씩 정상적으로 움직이고, 폭락일엔 10%도 넘는다.
    // 그런 날일수록 데이터가 필요하므로 크기로 막지 않는다.
    @Test
    fun `큰 폭의 변동은 막지 않는다`() {
        // -12% 급락: price 2200, change -300 → prevClose 2500, rate -12.00
        val crash = IndexQuote("KOSPI", BigDecimal("2200"), BigDecimal("2500"), BigDecimal("-300"), BigDecimal("-12.00"))

        assertThat(guards.check(crash)).isEmpty()
    }

    // 소수점 위치가 밀리거나 단위가 %↔비율로 바뀌면 여기서 잡힌다.
    @Test
    fun `등락률이 값과 어긋나면 걸린다`() {
        // 실제로는 3.68%인데 응답이 0.0368(비율)로 온 경우
        assertThat(guards.check(realQuote(rate = "0.0368")))
            .anyMatch { it.contains("등락률") }
    }

    // 위 테스트는 보고값이 계산값보다 "작은" 쪽만 본다. 비교에서 abs()가 빠져도
    // 그 케이스는 여전히 잡히므로 한쪽 방향만으로는 abs()가 고정되지 않는다.
    @Test
    fun `등락률이 계산값보다 커도 걸린다`() {
        assertThat(guards.check(realQuote(rate = "9.99")))
            .anyMatch { it.contains("등락률") }
    }

    // KIS는 소수점 둘째 자리로 반올림해 준다(3.6799 → 3.68). 그 폭은 허용해야 한다.
    @Test
    fun `반올림 오차는 통과시킨다`() {
        assertThat(guards.check(realQuote(rate = "3.67"))).isEmpty()
        assertThat(guards.check(realQuote(rate = "3.69"))).isEmpty()
    }

    // 파싱 실패가 0으로 떨어지면 지수가 0이 된다. 지수는 0이 될 수 없다.
    @Test
    fun `현재가가 0 이하면 걸린다`() {
        assertThat(guards.check(realQuote(price = "0", change = "0", rate = "0")))
            .anyMatch { it.contains("현재가") }
        assertThat(guards.check(IndexQuote("KOSPI", BigDecimal("-1"), BigDecimal("1"), BigDecimal("-2"), BigDecimal("-200"))))
            .anyMatch { it.contains("현재가") }
    }

    // 전일종가가 0이면 등락률을 계산할 수 없다. 0으로 나누어 터지지 말 것.
    @Test
    fun `전일종가가 0이면 등락률 검사를 건너뛰고 터지지 않는다`() {
        val q = IndexQuote("KOSPI", BigDecimal("100"), BigDecimal.ZERO, BigDecimal("100"), BigDecimal("0"))

        assertThat(guards.check(q)).isNotNull   // 예외가 나지 않는 것이 요점
    }

    @Test
    fun `문제가 여럿이면 모두 담는다`() {
        val bad = IndexQuote("KOSPI", BigDecimal("0"), BigDecimal("100"), BigDecimal("-100"), BigDecimal("999"))

        assertThat(guards.check(bad)).hasSizeGreaterThan(1)
    }
}
