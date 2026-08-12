package com.allfolio.fx.hana

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate

class HanaFxParserTest {

    private val parser = HanaFxParser()

    /** 11개 컬럼: 통화·현찰사실때(환율,스프레드)·현찰파실때(환율,스프레드)·송금보낼때·송금받을때·외화수표파실때·매매기준율·환가료율·미화환산율 */
    private fun row(name: String, vararg cells: String) =
        "<tr><td>$name</td>" + cells.joinToString("") { "<td>$it</td>" } + "</tr>"

    /** 실제 화면처럼 th 헤더를 항상 붙인다 — 헤더가 버린 행으로 세어지면 모든 skipped 단언이 깨진다 */
    private fun page(meta: String, vararg rows: String) = """
        <html><body>
          <div>$meta</div>
          <table>
            <thead><tr><th>통화</th><th>매매기준율</th></tr></thead>
            <tbody>${rows.joinToString("")}</tbody>
          </table>
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
    fun `소액 100단위 통화는 소수 넷째 자리까지 남긴다`() {
        // VND(100) 매매기준율 5.32 → 1동당 0.0532. 스케일이 2였다면 0.05가 되어 6% 어긋난다.
        // JPY만으로는 이 자리를 못 잡는다 — 950/100은 스케일 2에서도 9.50으로 멀쩡해 보인다.
        val vnd = row("베트남 VND(100)",
            "5.60", "1.75", "5.04", "1.75", "5.37", "5.27", "5.30",
            "5.32", "2.5", "0.0038")

        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", vnd))

        val row = result.rows.single()
        assertThat(row.currency).isEqualTo("VND")
        assertThat(row.baseRate).isEqualByComparingTo("0.0532")
        assertThat(row.cashBuy).isEqualByComparingTo("0.0560")
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
    fun `th 헤더 행은 버린 행으로 세지 않는다`() {
        // 헤더는 td가 0개다. 이걸 이상 행으로 세면 정상 수집마다 skipped≥1과 WARN이 찍혀
        // 어드민이 보는 skipped 값이 늘 거짓말을 한다.
        val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow))

        assertThat(result.rows).hasSize(1)
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `버린 행은 세 갈래 모두 통화명을 WARN에 남긴다`() {
        // skipped를 개수만으로 둔 근거가 "WARN이 상세를 남긴다"이다. 집계만 찍으면
        // skipped=12를 본 운영자가 어느 통화가 빠졌는지 알 길이 없다.
        // 버리는 갈래가 셋(컬럼 수·통화 코드·매매기준율)이라 셋 다 이름을 남겨야 한다.
        // 매매기준율 셀에만 sentinel을 넣어 "이름"뿐 아니라 "그 셀 원문"이 실리는지도 가린다.
        val badRate = row("영국 GBP",
            "1,414.50", "1.75", "1,365.50", "1.75", "1,404.00", "1,376.00", "1,375.00",
            "점검중", "2.5", "1.0")
        val noCode = row("합계",
            "1,414.50", "1.75", "1,365.50", "1.75", "1,404.00", "1,376.00", "1,375.00",
            "1,390.00", "2.5", "1.0")
        val short = "<tr><td>스위스 CHF</td><td>1,700.00</td></tr>"

        val logger = LoggerFactory.getLogger(HanaFxParser::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val result = parser.parse(page("기준일 : 2026년08월11일 (32회차)", usdRow, badRate, noCode, short))

            assertThat(result.skipped).isEqualTo(3)
            val logged = appender.list.joinToString("\n") { it.formattedMessage }
            assertThat(logged).contains("영국 GBP")   // 매매기준율 이상
            assertThat(logged).contains("점검중")     // 문제된 셀 원문
            assertThat(logged).contains("합계")       // 통화 코드 없음
            assertThat(logged).contains("스위스 CHF") // 컬럼 수 불일치
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `앞뒤에 다른 테이블이 있어도 환율 테이블을 고른다`() {
        // 앞의 무행 레이아웃 테이블을 위치로 집으면 rows=0·skipped=0·WARN 없는
        // "깨끗해 보이는" 빈 스냅샷이 나온다. 위치가 아니라 내용으로 골라야 한다.
        val html = """
            <html><body>
              <div>기준일 : 2026년08월11일 (32회차)</div>
              <table><tbody></tbody></table>
              <table><tbody><tr><td>메뉴</td><td>환율조회</td></tr></tbody></table>
              <table><tbody>$usdRow</tbody></table>
              <table><tbody><tr><td>안내</td></tr></tbody></table>
            </body></html>
        """.trimIndent()

        val result = parser.parse(html)

        assertThat(result.rows.single().currency).isEqualTo("USD")
        // 고른 테이블 밖의 행은 아예 보지 않는다 — 남의 테이블 때문에 skipped가 오르면 안 된다
        assertThat(result.skipped).isZero()
    }

    @Test
    fun `11컬럼 행이 하나도 없으면 예외로 올린다`() {
        assertThatThrownBy { parser.parse(page("기준일 : 2026년08월11일 (32회차)")) }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("테이블")
    }

    @Test
    fun `기준일을 못 읽으면 예외로 올린다`() {
        assertThatThrownBy { parser.parse(page("점검 중입니다", usdRow)) }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("기준일")
    }

    @Test
    fun `기준일이 실재하지 않는 날짜면 도메인 예외로 올린다`() {
        // LocalDate.of가 raw DateTimeException을 던지면 HanaFxParseException만 잡는
        // 수집기가 통째로 놓친다
        assertThatThrownBy { parser.parse(page("기준일 : 2026년13월45일 (32회차)", usdRow)) }
            .isInstanceOf(HanaFxParseException::class.java)
            .hasMessageContaining("기준일")
    }

    @Test
    fun `제로패딩 없는 기준일도 읽는다`() {
        val result = parser.parse(page("기준일 : 2026년8월1일 (3회차)", usdRow))

        assertThat(result.baseDate).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(result.roundNo).isEqualTo(3)
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
