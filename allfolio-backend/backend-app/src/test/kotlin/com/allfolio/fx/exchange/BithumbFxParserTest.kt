package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BithumbFxParserTest {

    private val parser = BithumbFxParser(ObjectMapper())

    /** 2026-08-12 api.bithumb.com/public/ticker/USDT_KRW 실제 응답에서 필드를 줄인 것 */
    private val realResponse = """
        {"status":"0000","data":{"opening_price":"1409","closing_price":"1409",
          "min_price":"1407","max_price":"1411","prev_closing_price":"1408",
          "date":"1786512440962"}}
    """.trimIndent()

    /** 2026-08-12 실측: 잘못된 심볼도 HTTP 200으로 오고 data가 아예 없다 */
    private val errorResponse = """{"status":"5500","message":"상장 코인이 아닙니다."}"""

    @Test
    fun `실제 응답에서 closing_price를 읽는다`() {
        assertThat(parser.parse(realResponse)).isEqualByComparingTo("1409")
    }

    @Test
    fun `status가 0000이 아니면 예외 - HTTP 200이라 이 검사가 유일한 방어선이다`() {
        assertThatThrownBy { parser.parse(errorResponse) }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("5500")
    }

    @Test
    fun `status가 정상인데 data가 없으면 예외`() {
        assertThatThrownBy { parser.parse("""{"status":"0000"}""") }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("data")
    }

    @Test
    fun `closing_price가 숫자 문자열이 아니면 예외`() {
        val json = """{"status":"0000","data":{"closing_price":"N/A"}}"""

        assertThatThrownBy { parser.parse(json) }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `JSON이 아니면 예외`() {
        assertThatThrownBy { parser.parse("<html>점검중</html>") }
            .isInstanceOf(FxQuoteException::class.java)
    }
}
