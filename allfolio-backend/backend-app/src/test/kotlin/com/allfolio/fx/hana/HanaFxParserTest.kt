package com.allfolio.fx.hana

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class HanaFxParserTest {

    private val parser = HanaFxParser()

    /** 11개 컬럼: 통화·현찰사실때(환율,스프레드)·현찰파실때(환율,스프레드)·송금보낼때·송금받을때·외화수표파실때·매매기준율·환가료율·미화환산율 */
    private fun row(name: String, vararg cells: String) =
        "<tr><td>$name</td>" + cells.joinToString("") { "<td>$it</td>" } + "</tr>"

    private fun page(meta: String, vararg rows: String) = """
        <html><body>
          <div>$meta</div>
          <table><tbody>${rows.joinToString("")}</tbody></table>
        </body></html>
    """.trimIndent()

    private val usdRow = row("미국 USD",
        "1,414.50", "1.75", "1,365.50", "1.75", "1,404.00", "1,376.00", "1,375.00",
        "1,390.00", "2.5", "1.0")

    @Test
    fun `기준일과 회차를 뽑는다`() {
        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow))

        assertThat(result.baseDate).isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(result.roundNo).isEqualTo(32)
    }

    @Test
    fun `통화명에서 3자리 코드를 뽑고 환율 여섯 개를 읽는다`() {
        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow))

        val usd = result.rows.single()
        assertThat(usd.currency).isEqualTo("USD")
        assertThat(usd.baseRate).isEqualByComparingTo("1390.00")
        assertThat(usd.cashBuy).isEqualByComparingTo("1414.50")
        assertThat(usd.cashSell).isEqualByComparingTo("1365.50")
        assertThat(usd.remitSend).isEqualByComparingTo("1404.00")
        assertThat(usd.remitReceive).isEqualByComparingTo("1376.00")
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `100단위 통화는 환율만 100으로 나눈다`() {
        // 일본 JPY(100): 매매기준율 950 → 1엔당 9.5. 스프레드·환가료율·미화환산율은 그대로
        val jpy = row("일본 JPY(100)",
            "966.00", "1.75", "934.00", "1.75", "959.00", "941.00", "940.00",
            "950.00", "2.5", "0.68")

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", jpy))

        val row = result.rows.single()
        assertThat(row.currency).isEqualTo("JPY")
        assertThat(row.baseRate).isEqualByComparingTo("9.50")
        assertThat(row.cashBuy).isEqualByComparingTo("9.66")
        assertThat(row.remitSend).isEqualByComparingTo("9.59")
    }

    @Test
    fun `컬럼 수가 11이 아닌 행은 버리고 센다`() {
        val short = "<tr><td>미국 USD</td><td>1,390.00</td></tr>"

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow, short))

        assertThat(result.rows).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `통화 코드를 못 뽑는 행은 버리고 센다`() {
        val noCode = row("합계",
            "1,414.50", "1.75", "1,365.50", "1.75", "1,404.00", "1,376.00", "1,375.00",
            "1,390.00", "2.5", "1.0")

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow, noCode))

        assertThat(result.rows).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `매매기준율이 숫자가 아닌 행은 버리고 센다`() {
        val dash = row("영국 GBP",
            "-", "-", "-", "-", "-", "-", "-", "-", "-", "-")

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow, dash))

        assertThat(result.rows).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    // 지정 목록 밖에서 추가: 파서의 `> 0` 방어가 어느 테스트에도 안 걸려 있었다.
    // 고시 중단 통화가 0.00으로 내려오면 0을 유효 환율로 저장해 평가가 망가진다.
    @Test
    fun `매매기준율이 0인 행은 버리고 센다`() {
        val zero = row("영국 GBP",
            "0.00", "1.75", "0.00", "1.75", "0.00", "0.00", "0.00",
            "0.00", "2.5", "1.0")

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow, zero))

        assertThat(result.rows).hasSize(1)
        assertThat(result.skipped).isEqualTo(1)
    }

    @Test
    fun `테이블이 비면 빈 결과를 준다`() {
        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)"))

        assertThat(result.rows).isEmpty()
    }

    @Test
    fun `기준일을 못 읽으면 예외로 올린다`() {
        assertThatThrownBy { parser.parse(page("점검 중입니다", usdRow)) }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("기준일")
    }

    @Test
    fun `회차를 못 읽으면 예외로 올린다`() {
        assertThatThrownBy { parser.parse(page("기준일 : 2026년08월11일", usdRow)) }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("회차")
    }

    @Test
    fun `테이블이 아예 없으면 예외로 올린다`() {
        assertThatThrownBy { parser.parse("<html><body>기준일 : 2026년08월11일 (32회차)</body></html>") }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("테이블")
    }
}
