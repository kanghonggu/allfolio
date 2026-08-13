package com.allfolio.fx.upbit

import com.allfolio.fx.DailyRate
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class UpbitCandleParserTest {

    private val parser = UpbitCandleParser(ObjectMapper())

    /** 2026-08-13 api.upbit.com/v1/candles/days 실제 응답에서 필드를 줄인 것 (최신순 내림차순) */
    private val realResponse = """
        [{"market":"KRW-BTC","candle_date_time_utc":"2026-08-02T00:00:00",
          "candle_date_time_kst":"2026-08-02T09:00:00","opening_price":90557000.0,
          "high_price":91398000.0,"low_price":90419000.0,"trade_price":90890000.0},
         {"market":"KRW-BTC","candle_date_time_utc":"2026-08-01T00:00:00",
          "candle_date_time_kst":"2026-08-01T09:00:00","opening_price":90360000.0,
          "high_price":90818000.0,"low_price":89800000.0,"trade_price":90557000.0}]
    """.trimIndent()

    @Test
    fun `종가와 KST 날짜를 뽑는다`() {
        val rates = parser.parse(realResponse).rates

        assertThat(rates).hasSize(2)
        assertThat(rates[0]).isEqualTo(DailyRate(LocalDate.of(2026, 8, 2), java.math.BigDecimal("9.089E+7")))
        assertThat(rates[1].baseDate).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(rates[1].rateKrw).isEqualByComparingTo("90557000")
    }

    @Test
    fun `UTC가 아니라 KST 날짜를 쓴다`() {
        // candle_date_time_utc는 2026-08-01T00:00, kst는 2026-08-01T09:00으로 같은 날이지만
        // 우리 도메인(cash_flow.flow_date)이 KST라 kst를 봐야 한다. utc를 쓰면 어떤 날은 하루 밀린다.
        val json = """
            [{"market":"KRW-ETH","candle_date_time_utc":"2026-07-31T00:00:00",
              "candle_date_time_kst":"2026-07-31T09:00:00","trade_price":2674000.0}]
        """.trimIndent()

        assertThat(parser.parse(json).rates.single().baseDate).isEqualTo(LocalDate.of(2026, 7, 31))
    }

    @Test
    fun `빈 배열이면 빈 리스트 - 예외가 아니다`() {
        // 구간에 데이터가 없는 것은 정상 응답이다. 중단 판단은 호출자(백필 서비스)가 한다.
        val result = parser.parse("[]")

        assertThat(result.rates).isEmpty()
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `trade_price가 없는 캔들은 건너뛰고 나머지는 살린다`() {
        val json = """
            [{"candle_date_time_kst":"2026-08-02T09:00:00","trade_price":90890000.0},
             {"candle_date_time_kst":"2026-08-01T09:00:00","opening_price":90360000.0}]
        """.trimIndent()

        assertThat(parser.parse(json).rates).hasSize(1)
    }

    @Test
    fun `날짜가 없는 캔들은 건너뛴다`() {
        val json = """[{"trade_price":90890000.0}]"""

        assertThat(parser.parse(json).rates).isEmpty()
    }

    @Test
    fun `JSON이 아니면 예외`() {
        assertThatThrownBy { parser.parse("<html>점검중</html>") }
            .isInstanceOf(UpbitCandleException::class.java)
    }

    @Test
    fun `배열이 아니면 예외 - 오류 응답이 객체로 온다`() {
        assertThatThrownBy { parser.parse("""{"error":{"name":"invalid_market"}}""") }
            .isInstanceOf(UpbitCandleException::class.java)
    }

    @Test
    fun `trade_price가 0이면 건너뛴다`() {
        val json = """[{"candle_date_time_kst":"2026-08-01T09:00:00","trade_price":0}]"""

        val result = parser.parse(json)

        assertThat(result.rates).isEmpty()
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `trade_price가 음수면 건너뛴다`() {
        val json = """[{"candle_date_time_kst":"2026-08-01T09:00:00","trade_price":-1}]"""

        val result = parser.parse(json)

        assertThat(result.rates).isEmpty()
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `날짜가 있어도 형식이 깨졌으면 건너뛴다`() {
        val json = """[{"candle_date_time_kst":"9999-99-99T09:00:00","trade_price":90890000.0}]"""

        val result = parser.parse(json)

        assertThat(result.rates).isEmpty()
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `버린 행 수를 센다`() {
        val json = """
            [{"candle_date_time_kst":"2026-08-02T09:00:00","trade_price":90890000.0},
             {"candle_date_time_kst":"2026-08-01T09:00:00","trade_price":0},
             {"trade_price":90360000.0}]
        """.trimIndent()

        val result = parser.parse(json)

        assertThat(result.rates).hasSize(1)
        assertThat(result.skipped).isEqualTo(2)
    }
}
