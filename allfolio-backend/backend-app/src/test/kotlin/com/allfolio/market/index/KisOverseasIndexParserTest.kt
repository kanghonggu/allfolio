package com.allfolio.market.index

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class KisOverseasIndexParserTest {

    private val parser = KisOverseasIndexParser()

    /** 2026-08-13 운영 실측 (HK#HS, 하락일) */
    private fun realResponse(
        prpr: String = "25365.14",
        vrss: String = "-75.03",
        sign: String = "5",
        ctrt: String = "-0.29",
        clpr: String = "25440.17",
        name: String = "항셍지수",
        bars: List<Pair<String, String>> = listOf("20260813" to "25365.14", "20260812" to "25440.17"),
    ) = mapOf<String, Any?>(
        "output1" to mapOf(
            "ovrs_nmix_prpr" to prpr,
            "ovrs_nmix_prdy_vrss" to vrss,
            "prdy_vrss_sign" to sign,
            "prdy_ctrt" to ctrt,
            "ovrs_nmix_prdy_clpr" to clpr,
            "hts_kor_isnm" to name,
        ),
        "output2" to bars.map { (d, p) -> mapOf("stck_bsop_date" to d, "ovrs_nmix_prpr" to p) },
    )

    // 하락일 응답이라 부호 규약이 드러나 있다. 검산: 25365.14 - (-75.03) = 25440.17 = 전일 봉의 종가,
    // -75.03/25440.17*100 = -0.2949 ≈ -0.29. 이 네 값이 서로 맞아야 Task 3의 가드가 의미를 갖는다.
    @Test
    fun `실측 응답을 그대로 파싱한다`() {
        val bar = parser.parse("HSI", realResponse())

        assertThat(bar.quote.indexCode).isEqualTo("HSI")
        assertThat(bar.quote.price).isEqualByComparingTo("25365.14")
        assertThat(bar.quote.change).isEqualByComparingTo("-75.03")
        assertThat(bar.quote.changeRate).isEqualByComparingTo("-0.29")
        assertThat(bar.quote.prevClose).isEqualByComparingTo("25440.17")
    }

    // **국내와 가장 다른 점이다.** 국내 수집은 "지금이 며칠인가"를 시계에서 얻지만,
    // 해외는 응답이 거래일을 들고 온다. 시계로 유추하면 한국 시각 기준으로 하루가 밀려
    // 미국 지수의 금요일 봉이 토요일 자로 저장된다.
    @Test
    fun `거래일을 응답에서 읽는다`() {
        val bar = parser.parse("HSI", realResponse())

        assertThat(bar.tradeDate).isEqualTo(LocalDate.of(2026, 8, 13))
    }

    // 한국 월요일 아침에 보는 S&P의 "전일"은 미국 금요일이다. 거래일에서 하루를 빼면 틀린다 —
    // 주말·현지 공휴일이 그대로 구멍이다. 이 값이 없으면 화면이 어느 날 대비인지 말할 수 없다.
    @Test
    fun `전일 종가의 날짜를 두 번째 봉에서 읽는다`() {
        val bar = parser.parse("HSI", realResponse())

        assertThat(bar.prevCloseDate).isEqualTo(LocalDate.of(2026, 8, 12))
    }

    // 상장 직후나 긴 연휴 뒤엔 조회 구간에 봉이 하나만 들어온다. 그때 날짜를 지어내면
    // (거래일 -1 같은 식으로) 존재하지 않는 거래일을 화면이 주장하게 된다. 모르면 null이다.
    @Test
    fun `봉이 하나면 전일 날짜는 null이다`() {
        val bar = parser.parse("HSI", realResponse(bars = listOf("20260813" to "25365.14")))

        assertThat(bar.tradeDate).isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(bar.prevCloseDate).isNull()
    }

    // prevClose는 price - change로 **계산**하고, 응답이 준 값은 여기 따로 담는다.
    // Task 3의 가드가 이 둘을 대조해 파싱이 어긋났는지 본다 —
    // 응답값을 prevClose에 그대로 넣으면 그 교차검증이 자기 자신과의 비교가 되어 무의미해진다.
    @Test
    fun `응답의 전일종가를 그대로 보관한다`() {
        val bar = parser.parse("HSI", realResponse())

        assertThat(bar.reportedPrevClose).isEqualByComparingTo("25440.17")
        assertThat(bar.quote.prevClose).isEqualByComparingTo(bar.reportedPrevClose)
    }

    // 위 테스트는 실측 픽스처라 역산값과 응답값이 우연히 같다 — 그래서 prevClose에 응답값을
    // 그대로 넣는 구현도 통과해 버린다. 둘을 갈라놓아야 "역산 vs 응답"이라는 Task 3 가드의 전제가 고정된다.
    @Test
    fun `전일종가는 역산하고 응답값은 따로 담는다`() {
        val bar = parser.parse("HSI", realResponse(clpr = "99999.99"))

        assertThat(bar.quote.prevClose).isEqualByComparingTo("25440.17")
        assertThat(bar.reportedPrevClose).isEqualByComparingTo("99999.99")
    }

    // 현재가는 output1에도 같은 값으로 들어 있어 실측 픽스처만으로는 어느 쪽에서 읽는지 고정되지 않는다.
    // 거래일과 값이 같은 봉에서 와야 짝이 어긋날 수 없다 — output1이 최신 봉과 갈라지는 날
    // 거래일 20260813 자로 다른 날 가격이 저장돼도 아무 데서도 티가 나지 않는다.
    @Test
    fun `현재가는 요약이 아니라 봉에서 읽는다`() {
        val bar = parser.parse("HSI", realResponse(prpr = "99999.99"))

        assertThat(bar.quote.price).isEqualByComparingTo("25365.14")
    }

    // 마스터엔 한 글자 차이인 지수들이 줄줄이 붙어 있다(항셍 옆 HSCE·HK#HSSI).
    // 엉뚱한 코드의 응답도 값끼리는 일관돼 IndexGuards를 그대로 통과하므로,
    // 틀린 코드를 넣었을 때 잡을 방법은 KIS가 돌려준 이름뿐이다.
    @Test
    fun `KIS가 준 이름을 보관한다`() {
        val bar = parser.parse("HSI", realResponse())

        assertThat(bar.nameFromKis).isEqualTo("항셍지수")
    }

    // 해외는 값에 마이너스를 싣는 것으로 실측 확인됐지만, 그 관례에 기대지 않는다.
    // 방향은 prdy_vrss_sign에서만 오므로 KIS가 어느 날 부호를 빼도 결과가 같아야 한다.
    @Test
    fun `부호가 값에 실려 있든 없든 같은 결과를 낸다`() {
        val withSign = parser.parse("HSI", realResponse(vrss = "-75.03", ctrt = "-0.29", sign = "5"))
        val withoutSign = parser.parse("HSI", realResponse(vrss = "75.03", ctrt = "0.29", sign = "5"))

        assertThat(withoutSign.quote.change).isEqualByComparingTo("-75.03")
        assertThat(withoutSign.quote.changeRate).isEqualByComparingTo("-0.29")
        assertThat(withSign.quote.change).isEqualByComparingTo(withoutSign.quote.change)
        assertThat(withSign.quote.changeRate).isEqualByComparingTo(withoutSign.quote.changeRate)
    }

    // 절댓값은 크기를 남기면서 모순을 지운다. 거부하지 않으면 하락한 날이 +75.03으로 저장되고
    // IndexGuards도 못 잡는다 — 같은 방향을 change와 changeRate에 똑같이 곱하므로 둘은 늘 서로 맞는다.
    @Test
    fun `값이 음수인데 부호가 상승이면 거부한다`() {
        assertThatThrownBy { parser.parse("HSI", realResponse(vrss = "-75.03", sign = "2")) }
            .isInstanceOf(KisIndexException::class.java)
            .hasMessageContaining("부호")
    }

    // 봉이 없으면 거래일도 현재가도 없다. 빈 응답을 통과시키면 그 뒤 전부가 추측이 된다.
    @Test
    fun `output2가 비면 거부한다`() {
        assertThatThrownBy { parser.parse("HSI", realResponse(bars = emptyList())) }
            .isInstanceOf(KisIndexException::class.java)
    }

    @Test
    fun `output1이 없으면 거부한다`() {
        assertThatThrownBy { parser.parse("HSI", realResponse().minus("output1")) }
            .isInstanceOf(KisIndexException::class.java)
    }
}
