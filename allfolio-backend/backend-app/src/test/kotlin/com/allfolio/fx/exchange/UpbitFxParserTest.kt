package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UpbitFxParserTest {

    private val parser = UpbitFxParser(ObjectMapper())

    /** 2026-08-12 api.upbit.com/v1/ticker?markets=KRW-USDT,KRW-BTC,KRW-ETH 실제 응답에서 필드를 줄인 것 */
    private val realResponse = """
        [{"market":"KRW-USDT","trade_price":1409.00000000,"opening_price":1409.0},
         {"market":"KRW-BTC","trade_price":89825000.00000000,"opening_price":89800000.0},
         {"market":"KRW-ETH","trade_price":2663000.00000000,"opening_price":2660000.0}]
    """.trimIndent()

    @Test
    fun `세 마켓을 심볼로 키를 바꿔 돌려준다`() {
        val rates = parser.parse(realResponse)

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(rates["USDT"]).isEqualByComparingTo("1409.0")
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000.0")
        assertThat(rates["ETH"]).isEqualByComparingTo("2663000.0")
    }

    @Test
    fun `배열 순서가 뒤바뀌어도 market 필드로 맞춘다`() {
        // Upbit이 markets= 순서를 지킨다는 보장이 없다. 인덱스로 매칭하면 조용히 뒤바뀐다 —
        // BTC 가격이 USDT 자리에 들어가면 자산이 6만 배가 된다.
        val shuffled = """
            [{"market":"KRW-ETH","trade_price":2663000.0},
             {"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-BTC","trade_price":89825000.0}]
        """.trimIndent()

        val rates = parser.parse(shuffled)

        assertThat(rates["USDT"]).isEqualByComparingTo("1409.0")
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000.0")
        assertThat(rates["ETH"]).isEqualByComparingTo("2663000.0")
    }

    @Test
    fun `일부 마켓만 와도 온 것만 돌려준다 - 나머지는 다음 소스가 채운다`() {
        val partial = """[{"market":"KRW-USDT","trade_price":1409.0}]"""

        val rates = parser.parse(partial)

        assertThat(rates).containsOnlyKeys("USDT")
    }

    @Test
    fun `모르는 마켓은 무시한다`() {
        val extra = """
            [{"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-DOGE","trade_price":300.0}]
        """.trimIndent()

        assertThat(parser.parse(extra)).containsOnlyKeys("USDT")
    }

    @Test
    fun `소수점이 있는 가격도 정밀도를 잃지 않는다`() {
        val json = """[{"market":"KRW-USDT","trade_price":1408.55}]"""

        assertThat(parser.parse(json)["USDT"]).isEqualByComparingTo("1408.55")
    }

    @Test
    fun `빈 배열이면 예외 - 조용히 빈 맵을 돌려주면 실패가 안 보인다`() {
        assertThatThrownBy { parser.parse("[]") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("비어")
    }

    @Test
    fun `아는 마켓이 하나도 없으면 예외`() {
        assertThatThrownBy { parser.parse("""[{"market":"KRW-DOGE","trade_price":300.0}]""") }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `trade_price가 없는 항목은 건너뛰고 나머지는 살린다`() {
        val json = """
            [{"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-BTC","opening_price":89800000.0}]
        """.trimIndent()

        assertThat(parser.parse(json)).containsOnlyKeys("USDT")
    }

    @Test
    fun `trade_price가 숫자가 아니면 그 항목만 건너뛴다`() {
        val json = """
            [{"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-BTC","trade_price":"89800000"}]
        """.trimIndent()

        assertThat(parser.parse(json)).containsOnlyKeys("USDT")
    }

    @Test
    fun `JSON이 아니면 예외`() {
        assertThatThrownBy { parser.parse("<html>maintenance</html>") }
            .isInstanceOf(FxQuoteException::class.java)
    }
}
