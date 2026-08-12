package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UpbitFxParserTest {

    private val parser = UpbitFxParser(ObjectMapper())

    /** 2026-08-12 api.upbit.com/v1/ticker?markets=KRW-USDT 실제 응답에서 필드를 줄인 것 */
    private val realResponse = """
        [{"market":"KRW-USDT","trade_date":"20260812","trade_time":"052720",
          "opening_price":1409.00000000,"high_price":1411.00000000,
          "low_price":1407.00000000,"trade_price":1408.00000000,
          "prev_closing_price":1409.00000000,"timestamp":1786512440253}]
    """.trimIndent()

    @Test
    fun `실제 응답에서 trade_price를 읽는다`() {
        assertThat(parser.parse(realResponse)).isEqualByComparingTo("1408.0")
    }

    @Test
    fun `소수점이 있는 가격도 정밀도를 잃지 않는다`() {
        val json = """[{"market":"KRW-USDT","trade_price":1408.55}]"""

        assertThat(parser.parse(json)).isEqualByComparingTo("1408.55")
    }

    @Test
    fun `빈 배열이면 예외 - 조용히 0을 돌려주면 모든 평가가 오염된다`() {
        assertThatThrownBy { parser.parse("[]") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("비어")
    }

    @Test
    fun `trade_price 필드가 없으면 예외`() {
        val json = """[{"market":"KRW-USDT","opening_price":1409.0}]"""

        assertThatThrownBy { parser.parse(json) }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("trade_price")
    }

    @Test
    fun `JSON이 아니면 예외`() {
        assertThatThrownBy { parser.parse("<html>maintenance</html>") }
            .isInstanceOf(FxQuoteException::class.java)
    }
}
