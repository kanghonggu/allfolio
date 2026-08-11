package com.allfolio.fx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EcosResponseParserTest {

    private val parser = EcosResponseParser()

    @Test
    fun `정상 응답에서 날짜와 값을 뽑는다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"STAT_CODE":"X","TIME":"20250808","DATA_VALUE":"1385.5","UNIT_NAME":"원"},
              {"STAT_CODE":"X","TIME":"20250811","DATA_VALUE":"1390.2","UNIT_NAME":"원"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result.rates).hasSize(2)
        assertThat(result.rates[0].baseDate).isEqualTo(LocalDate.of(2025, 8, 8))
        assertThat(result.rates[0].rateKrw).isEqualByComparingTo("1385.5")
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `값이 비었거나 0 이하인 행은 건너뛰고 센다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":4,"row":[
              {"TIME":"20250808","DATA_VALUE":"1385.5"},
              {"TIME":"20250809","DATA_VALUE":""},
              {"TIME":"20250810","DATA_VALUE":"0"},
              {"TIME":"20250811","DATA_VALUE":"-1"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result.rates).hasSize(1)
        assertThat(result.skipped).isEqualTo(3)
    }

    @Test
    fun `날짜 형식이 어긋난 행은 건너뛴다`() {
        val json = """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"TIME":"2025Q3","DATA_VALUE":"1385.5"},
              {"TIME":"20250811","DATA_VALUE":"1390.2"}
            ]}}
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result.rates).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `ECOS 에러 응답은 예외로 올린다`() {
        val json = """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}"""

        assertThatThrownBy { parser.parse(json) }
            .isInstanceOf(EcosApiException::class.java)
            .hasMessageContaining("INFO-200")
    }

    @Test
    fun `예상 밖 형식은 예외로 올린다`() {
        assertThatThrownBy { parser.parse("""{"something":"else"}""") }
            .isInstanceOf(EcosApiException::class.java)
    }

    @Test
    fun `행이 0건이면 빈 결과를 준다`() {
        val result = parser.parse("""{"StatisticSearch":{"list_total_count":0,"row":[]}}""")

        assertThat(result.rates).isEmpty()
    }
}
