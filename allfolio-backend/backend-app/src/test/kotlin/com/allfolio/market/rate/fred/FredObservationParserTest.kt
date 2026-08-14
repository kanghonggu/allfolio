package com.allfolio.market.rate.fred

import com.allfolio.fx.RateValuePolicy
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FredObservationParserTest {

    private val parser = FredObservationParser(ObjectMapper())

    @Test
    fun `날짜와 값을 뽑는다`() {
        val json = """
            {"observations":[
              {"date":"2026-08-12","value":"4.24"},
              {"date":"2026-08-13","value":"4.25"}
            ]}
        """.trimIndent()

        val result = parser.parse(json, RateValuePolicy.PERCENT)

        assertThat(result.rows).hasSize(2)
        assertThat(result.rows[1].quoteDate).isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(result.rows[1].value).isEqualByComparingTo("4.25")
        assertThat(result.skipped).isZero()
    }

    /**
     * **FRED는 관측이 없는 날 값으로 마침표를 준다.** 휴일·미공표일이 그렇다.
     *
     * 이걸 값 검증으로는 절대 못 거른다 — 0으로 변환되면 [RateValuePolicy.PERCENT]가
     * 통과시키기 때문이다(0.00% 공표일이 실재해서 일부러 통과시킨다). 그래서 파싱 단계에서
     * 걸러 센다. 안 그러면 화면에 "미국채 10년 0.00%"가 그럴듯하게 뜬다.
     */
    @Test
    fun `결측 마침표는 값이 아니라 파싱 단계에서 걸러 센다`() {
        val json = """
            {"observations":[
              {"date":"2026-08-13","value":"4.25"},
              {"date":"2026-08-14","value":"."},
              {"date":"2026-08-15","value":""}
            ]}
        """.trimIndent()

        val result = parser.parse(json, RateValuePolicy.PERCENT)

        assertThat(result.rows.map { it.value })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(BigDecimal("4.25"))
        assertThat(result.skipped).isEqualTo(2)
    }

    /** 0%와 마이너스 금리는 실재한다 — 마침표와 달리 살려야 한다 */
    @Test
    fun `0과 마이너스는 살린다`() {
        val json = """
            {"observations":[
              {"date":"2026-08-12","value":"0"},
              {"date":"2026-08-13","value":"-0.25"}
            ]}
        """.trimIndent()

        val result = parser.parse(json, RateValuePolicy.PERCENT)

        assertThat(result.rows).hasSize(2)
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `날짜 형식이 어긋난 행은 건너뛰고 센다`() {
        val json = """{"observations":[{"date":"2026-Q3","value":"4.25"}]}"""

        val result = parser.parse(json, RateValuePolicy.PERCENT)

        assertThat(result.rows).isEmpty()
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `observations가 없으면 예외다`() {
        assertThatThrownBy { parser.parse("""{"error_message":"Bad Request."}""", RateValuePolicy.PERCENT) }
            .isInstanceOf(FredApiException::class.java)
    }
}
