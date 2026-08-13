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

    /**
     * BigDecimal(double) 생성자를 타지 않는다는 것을 못 박는다.
     *
     * 클래스 KDoc이 설명하듯 asText()가 double을 피해 주지는 않는다 — readTree가 이미
     * DoubleNode를 만든다. 값이 정확한 건 Double.toString이 왕복 가능한 최단 표기를 내기 때문이다.
     * 하지만 누군가 `BigDecimal(node.asDouble())`로 바꾸면 0.1이
     * 0.1000000000000000055511151231257827로 펼쳐진다. 그 회귀를 여기서 잡는다.
     */
    @Test
    fun `BigDecimal double 생성자를 타지 않는다`() {
        // 0.1은 2진수로 정확히 표현되지 않는 대표값이다. KRW 시세로는 비현실적이지만
        // 생성자 선택을 가리는 데는 이 값이 가장 예리하다.
        val json = """[{"market":"KRW-USDT","trade_price":0.1}]"""

        val parsed = parser.parse(json).getValue("USDT")

        assertThat(parsed).isEqualByComparingTo("0.1")
        assertThat(parsed.toPlainString()).isEqualTo("0.1")   // BigDecimal(double)이면 0.1000...0555
    }

    /**
     * 큰 값이 지수 표기로 찍혀도 값은 정확하다 (로그의 `BTC=9.0047E+7`이 이것이다).
     */
    @Test
    fun `1e7 이상도 값이 정확하다 - 지수 표기는 표시일 뿐이다`() {
        val json = """[{"market":"KRW-BTC","trade_price":90047000.0}]"""

        val btc = parser.parse(json).getValue("BTC")

        assertThat(btc).isEqualByComparingTo("90047000")
        assertThat(btc.toPlainString()).isEqualTo("90047000")
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
