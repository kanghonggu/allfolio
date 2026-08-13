package com.allfolio.fx

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EcosResponseParserTest {

    private val parser = EcosResponseParser(ObjectMapper())

    @Test
    fun `정상 응답에서 날짜와 값을 뽑는다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"STAT_CODE":"X","TIME":"20250808","DATA_VALUE":"1385.5","UNIT_NAME":"원"},
              {"STAT_CODE":"X","TIME":"20250811","DATA_VALUE":"1390.2","UNIT_NAME":"원"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json, EcosValuePolicy.POSITIVE)

        assertThat(result.rates).hasSize(2)
        assertThat(result.rates[0].baseDate).isEqualTo(LocalDate.of(2025, 8, 8))
        assertThat(result.rates[0].value).isEqualByComparingTo("1385.5")
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `환율 정책은 값이 비었거나 0 이하인 행을 건너뛰고 센다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":4,"row":[
              {"TIME":"20250808","DATA_VALUE":"1385.5"},
              {"TIME":"20250809","DATA_VALUE":""},
              {"TIME":"20250810","DATA_VALUE":"0"},
              {"TIME":"20250811","DATA_VALUE":"-1"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json, EcosValuePolicy.POSITIVE)

        assertThat(result.rates).hasSize(1)
        assertThat(result.skipped).isEqualTo(3)
    }

    /**
     * 금리는 0.00%로 공표될 수 있고 마이너스 금리도 실재한다.
     * 환율 정책을 그대로 쓰면 그 날이 통째로 사라지는데, 예외도 안 나고 경고 로그만 남는다.
     */
    @Test
    fun `금리 정책은 0과 마이너스를 살리고 단위 오인만 거른다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":5,"row":[
              {"TIME":"20250808","DATA_VALUE":"3.5"},
              {"TIME":"20250809","DATA_VALUE":"0"},
              {"TIME":"20250810","DATA_VALUE":"-0.25"},
              {"TIME":"20250811","DATA_VALUE":"100.0001"},
              {"TIME":"20250812","DATA_VALUE":""}
            ]}}
        """.trimIndent()

        val result = parser.parse(json, EcosValuePolicy.PERCENT)

        assertThat(result.rates.map { it.value })
            .usingElementComparator(java.math.BigDecimal::compareTo)
            .containsExactly(
                java.math.BigDecimal("3.5"),
                java.math.BigDecimal("0"),
                java.math.BigDecimal("-0.25"),
            )
        assertThat(result.skipped).isEqualTo(2)
    }

    @Test
    fun `날짜 형식이 어긋난 행은 건너뛴다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"TIME":"2025Q3","DATA_VALUE":"1385.5"},
              {"TIME":"20250811","DATA_VALUE":"1390.2"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json, EcosValuePolicy.POSITIVE)

        assertThat(result.rates).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `ECOS 에러 응답은 예외로 올린다`() {
        val json = """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}"""

        assertThatThrownBy { parser.parse(json, EcosValuePolicy.POSITIVE) }
            .isInstanceOf(EcosApiException::class.java)
            .hasMessageContaining("INFO-200")
            .satisfies({ ex -> assertThat((ex as EcosApiException).code).isEqualTo("INFO-200") })
    }

    @Test
    fun `RESULT에 CODE가 없으면 빈 code로 예외를 올린다`() {
        val json = """{"RESULT":{"MESSAGE":"코드 없는 오류"}}"""

        assertThatThrownBy { parser.parse(json, EcosValuePolicy.POSITIVE) }
            .isInstanceOf(EcosApiException::class.java)
            .satisfies({ ex -> assertThat((ex as EcosApiException).code).isEmpty() })
    }

    @Test
    fun `예상 밖 형식은 예외로 올린다`() {
        assertThatThrownBy { parser.parse("""{"something":"else"}""", EcosValuePolicy.POSITIVE) }
            .isInstanceOf(EcosApiException::class.java)
    }

    @Test
    fun `행이 0건이면 빈 결과를 준다`() {
        val result = parser.parse("""{"StatisticSearch":{"list_total_count":0,"row":[]}}""", EcosValuePolicy.POSITIVE)

        assertThat(result.rates).isEmpty()
    }

    @Test
    fun `TIME이나 DATA_VALUE 키가 아예 없는 행도 빈 문자열과 동일하게 건너뛴다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"DATA_VALUE":"1385.5"},
              {"TIME":"20250811"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json, EcosValuePolicy.POSITIVE)

        assertThat(result.rates).isEmpty()
        assertThat(result.skipped).isEqualTo(2)
    }

    @Test
    fun `같은 TIME이 중복돼도 파서는 걸러내지 않고 그대로 통과시킨다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"TIME":"20250811","DATA_VALUE":"1385.5"},
              {"TIME":"20250811","DATA_VALUE":"1390.2"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json, EcosValuePolicy.POSITIVE)

        assertThat(result.rates).hasSize(2)
        assertThat(result.rates.map { it.baseDate }).containsExactly(
            LocalDate.of(2025, 8, 11),
            LocalDate.of(2025, 8, 11),
        )
        assertThat(result.skipped).isZero()
    }
}
