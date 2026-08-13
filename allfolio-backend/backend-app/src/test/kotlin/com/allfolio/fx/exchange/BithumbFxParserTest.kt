package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BithumbFxParserTest {

    private val parser = BithumbFxParser(ObjectMapper())

    /** 2026-08-12 api.bithumb.com/public/ticker/ALL_KRW 실제 응답에서 코인 수와 필드를 줄인 것 */
    private val realResponse = """
        {"status":"0000","data":{
          "BTC":{"opening_price":"89800000","closing_price":"89880000"},
          "ETH":{"opening_price":"2660000","closing_price":"2664000"},
          "USDT":{"opening_price":"1409","closing_price":"1410"},
          "DOGE":{"opening_price":"300","closing_price":"301"},
          "date":"1786521700219"}}
    """.trimIndent()

    @Test
    fun `아는 세 심볼만 뽑는다`() {
        val rates = parser.parse(realResponse)

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(rates["BTC"]).isEqualByComparingTo("89880000")
        assertThat(rates["ETH"]).isEqualByComparingTo("2664000")
        assertThat(rates["USDT"]).isEqualByComparingTo("1410")
    }

    @Test
    fun `data에 섞인 date 문자열에 걸려 넘어지지 않는다`() {
        // 실측: ALL_KRW의 data는 481개 키 중 480개가 코인이고 하나가 date 문자열이다.
        // 맵을 순회하는 구현이면 여기서 깨진다. 심볼을 키로 직접 꺼내면 구조적으로 안전하다.
        assertThat(parser.parse(realResponse)).doesNotContainKey("date")
    }

    @Test
    fun `일부 심볼만 있어도 있는 것만 돌려준다`() {
        val partial = """{"status":"0000","data":{"USDT":{"closing_price":"1410"},"date":"1"}}"""

        assertThat(parser.parse(partial)).containsOnlyKeys("USDT")
    }

    @Test
    fun `status가 0000이 아니면 예외 - HTTP 200이라 이 검사가 유일한 방어선이다`() {
        val error = """{"status":"5500","message":"상장 코인이 아닙니다."}"""

        assertThatThrownBy { parser.parse(error) }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("5500")
            .hasMessageContaining("상장 코인이 아닙니다")
    }

    @Test
    fun `status 필드가 아예 없으면 예외 - 없는 것을 정상으로 읽으면 안 된다`() {
        assertThatThrownBy { parser.parse("""{"data":{"BTC":{"closing_price":"1"}}}""") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("status=null")
    }

    @Test
    fun `status가 정상인데 data가 없으면 예외`() {
        assertThatThrownBy { parser.parse("""{"status":"0000"}""") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("data")
    }

    @Test
    fun `아는 심볼이 하나도 없으면 예외`() {
        val none = """{"status":"0000","data":{"DOGE":{"closing_price":"300"},"date":"1"}}"""

        assertThatThrownBy { parser.parse(none) }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `closing_price가 숫자가 아니면 그 심볼만 건너뛴다`() {
        val json = """
            {"status":"0000","data":{
              "BTC":{"closing_price":"N/A"},
              "USDT":{"closing_price":"1410"}}}
        """.trimIndent()

        assertThat(parser.parse(json)).containsOnlyKeys("USDT")
    }

    @Test
    fun `closing_price가 없으면 그 심볼만 건너뛴다`() {
        val json = """
            {"status":"0000","data":{
              "BTC":{"opening_price":"89800000"},
              "USDT":{"closing_price":"1410"}}}
        """.trimIndent()

        assertThat(parser.parse(json)).containsOnlyKeys("USDT")
    }

    @Test
    fun `JSON이 아니면 예외`() {
        assertThatThrownBy { parser.parse("<html>점검중</html>") }
            .isInstanceOf(FxQuoteException::class.java)
    }
}
