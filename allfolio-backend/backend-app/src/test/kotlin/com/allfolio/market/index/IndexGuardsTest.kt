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

    // 전일종가가 0이면 등락률을 계산할 수 없다. 0으로 나누어 터지지 말 것 —
    // 그렇다고 조용히 통과시켜도 안 된다. 반환이 비면 "저장해도 좋다"는 뜻이라
    // 검사를 건너뛰는 것이 곧 이 클래스의 유일한 내용 검사를 스스로 끄는 일이 된다.
    // (앞선 `isNotNull` 단언은 Kotlin List가 결코 null이 아니라 아무것도 못 잡았다.)
    @Test
    fun `전일종가가 0이면 터지지 않고 이상으로 잡는다`() {
        val q = IndexQuote("KOSPI", BigDecimal("100"), BigDecimal.ZERO, BigDecimal("100"), BigDecimal("0"))

        assertThat(guards.check(q)).anyMatch { it.contains("전일종가") }
    }

    // price - change라 전일종가는 음수가 될 수 있다. 현재가가 잘려서 온 응답
    // (prpr "100.00" + vrss "233.51")이 정확히 이 모양이고, 파싱은 멀쩡히 성공한다.
    @Test
    fun `전일종가가 음수면 이상으로 잡는다`() {
        val truncated = IndexQuote(
            "KOSPI",
            BigDecimal("100.00"),
            BigDecimal("100.00").subtract(BigDecimal("233.51")),
            BigDecimal("233.51"),
            BigDecimal("3.68"),
        )

        assertThat(guards.check(truncated)).anyMatch { it.contains("전일종가") }
    }

    @Test
    fun `문제가 여럿이면 모두 담는다`() {
        val bad = IndexQuote("KOSPI", BigDecimal("0"), BigDecimal("100"), BigDecimal("-100"), BigDecimal("999"))

        assertThat(guards.check(bad)).hasSizeGreaterThan(1)
    }

    // 해외는 전일종가를 응답이 직접 준다. 역산값과 어긋나면 필드가 밀렸거나 소수점이 틀린 것이다.
    // 등락률 검사와 독립적이다 — 등락률이 맞아도 이게 틀릴 수 있다.
    //
    // "전일종가"만 보고 단언하면 안 된다. 전일종가 0 이하 메시지와 등락률 어긋남 메시지에도
    // 같은 낱말이 들어 있어서, 구현이 엉뚱한 검사를 걸었는데도 통과할 수 있다.
    // 이 검사만이 낼 수 있는 것 — 응답값과 역산값 두 숫자가 함께 실렸는지 — 으로 가른다.
    @Test
    fun `응답이 준 전일종가가 역산값과 다르면 걸린다`() {
        assertThat(guards.check(realQuote(), reportedPrevClose = BigDecimal("6000.00")))
            .anyMatch { it.contains("6000") && it.contains("6345.53") }
    }

    @Test
    fun `응답이 준 전일종가가 역산값과 같으면 통과한다`() {
        assertThat(guards.check(realQuote(), reportedPrevClose = BigDecimal("6345.53"))).isEmpty()
    }

    // 국내는 응답에 전일종가가 없다. null은 "출처가 안 준다"는 뜻이지 "우리가 빠뜨렸다"가 아니다.
    @Test
    fun `전일종가를 안 주면 그 검사를 건너뛴다`() {
        assertThat(guards.check(realQuote(), reportedPrevClose = null)).isEmpty()
    }

    // 스케일이 달라도 값이 같으면 통과해야 한다. equals()로 비교하면 6345.53 != 6345.5300이다.
    @Test
    fun `스케일이 달라도 값이 같으면 통과한다`() {
        assertThat(guards.check(realQuote(), reportedPrevClose = BigDecimal("6345.5300"))).isEmpty()
    }

    // 역산값은 quote.prevClose가 아니라 price - change에서 와야 한다. 파서가 prevClose에
    // 응답값을 넣어 버리면(설계 문서의 매핑표가 한동안 그렇게 지시하고 있었다) quote.prevClose로
    // 비교하는 구현은 자기 자신을 비교하게 되어 영원히 안 걸린다 — 아래가 정확히 그 응답이다.
    // 등락률은 prevClose 6000 기준으로 맞춰 두었으므로(233.51/6000 = 3.89%) 이 검사만 걸린다.
    @Test
    fun `전일종가 필드에 응답값이 들어와도 역산으로 대조한다`() {
        val prevCloseHoldsReported = IndexQuote(
            indexCode = "HSI",
            price = BigDecimal("6579.04"),
            prevClose = BigDecimal("6000.00"),
            change = BigDecimal("233.51"),
            changeRate = BigDecimal("3.89"),
        )

        assertThat(guards.check(prevCloseHoldsReported, reportedPrevClose = BigDecimal("6000.00")))
            .singleElement()
            .satisfies({ assertThat(it).contains("6000").contains("6345.53") })
    }

    // 신규 검사는 다른 이상이 이미 잡혔을 때도 돌아야 한다. 두 메시지는 서로 다른 사실을 말하고,
    // 두 번째가 정답 전일종가를 실어 준다 — `if (anomalies.isEmpty())`로 감싸면 그게 사라진다.
    // 현재가가 잘려 온 응답(prpr "100.00" + vrss "233.51")이 정확히 이 모양이다.
    @Test
    fun `다른 이상이 이미 있어도 전일종가 교차검증은 돈다`() {
        val truncated = IndexQuote(
            indexCode = "HSI",
            price = BigDecimal("100.00"),
            prevClose = BigDecimal("100.00").subtract(BigDecimal("233.51")),
            change = BigDecimal("233.51"),
            changeRate = BigDecimal("3.68"),
        )

        val anomalies = guards.check(truncated, reportedPrevClose = BigDecimal("6345.53"))

        assertThat(anomalies).hasSize(2)
        assertThat(anomalies).anyMatch { it.contains("0 이하") }
        assertThat(anomalies).anyMatch { it.contains("6345.53") && it.contains("-133.51") }
    }
}
