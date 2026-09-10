package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class FifoRealizedPnlCalculatorTest {

    private val acct = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6) // 2026-06-01 ~ 2026-06-30

    private fun trade(type: StockTradeType, symbol: String?, qty: String, price: String, on: LocalDate, fee: String = "0") =
        StockTrade.create(
            accountId = acct, userId = user, tradeType = type, stockName = symbol ?: "?", symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal(price),
            totalAmount = BigDecimal(qty).multiply(BigDecimal(price)), fee = BigDecimal(fee), tax = BigDecimal.ZERO,
            tradedAt = on, memo = null,
        )

    @Test
    fun `당월 매수 후 부분매도 실현손익`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "AAA", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.SELL, "AAA", "4", "150", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["AAA"]).isEqualByComparingTo("200") // 4*(150-100)
    }

    @Test
    fun `이전월 매수 lot 원가가 당월 매도 실현손익에 반영된다 (경계)`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "BBB", "10", "100", LocalDate.of(2026, 5, 10)),
                trade(StockTradeType.SELL, "BBB", "4", "150", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["BBB"]).isEqualByComparingTo("200") // 옛 lot 원가 100 사용, 당월분만
    }

    @Test
    fun `이전월 매도는 당월에 포함되지 않는다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "CCC", "10", "100", LocalDate.of(2026, 5, 1)),
                trade(StockTradeType.SELL, "CCC", "5", "150", LocalDate.of(2026, 5, 15)), // 5월 실현(제외)
                trade(StockTradeType.SELL, "CCC", "5", "200", LocalDate.of(2026, 6, 15)), // 6월 실현
            ),
            period,
        )
        assertThat(r["CCC"]).isEqualByComparingTo("500") // 5*(200-100), 5월분 5*(150-100) 제외
    }

    @Test
    fun `당월 전량매도 종목도 실현손익이 잡힌다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "DDD", "10", "100", LocalDate.of(2026, 6, 3)),
                trade(StockTradeType.SELL, "DDD", "10", "150", LocalDate.of(2026, 6, 25)),
            ),
            period,
        )
        assertThat(r["DDD"]).isEqualByComparingTo("500")
    }

    @Test
    fun `신용매수 매도도 BUY SELL로 매핑된다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.CREDIT_BUY, "EEE", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.CREDIT_SELL, "EEE", "10", "120", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["EEE"]).isEqualByComparingTo("200")
    }

    @Test
    fun `배당 미수 심볼없음은 제외되고 매도없으면 0`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.DIVIDEND, "FFF", "0", "0", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.MARGIN, "FFF", "1", "100", LocalDate.of(2026, 6, 6)),
                trade(StockTradeType.BUY, "FFF", "10", "100", LocalDate.of(2026, 6, 7)), // 매수만
                trade(StockTradeType.BUY, null, "1", "1", LocalDate.of(2026, 6, 8)),      // symbol 없음 제외
            ),
            period,
        )
        assertThat(r["FFF"]).isEqualByComparingTo("0")
    }

    @Test
    fun `수수료는 실현손익에서 차감된다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "GGG", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.SELL, "GGG", "10", "150", LocalDate.of(2026, 6, 20), fee = "50"),
            ),
            period,
        )
        assertThat(r["GGG"]).isEqualByComparingTo("450") // 500 - 50
    }

    /** createdAt를 지정해 같은 날 거래의 입력순을 고정한다(create는 now()라 결정적이지 않음). */
    private fun tradeCreatedAt(
        type: StockTradeType,
        symbol: String,
        qty: String,
        price: String,
        on: LocalDate,
        createdAt: LocalDateTime,
    ) = StockTrade.reconstruct(
        id = UUID.randomUUID(), accountId = acct, userId = user, tradeType = type,
        stockName = symbol, symbol = symbol,
        quantity = BigDecimal(qty), price = BigDecimal(price),
        totalAmount = BigDecimal(qty).multiply(BigDecimal(price)),
        fee = BigDecimal.ZERO, tax = BigDecimal.ZERO,
        tradedAt = on, memo = null, createdAt = createdAt,
    )

    @Test
    fun `같은 날 거래는 입력 목록 순서가 아니라 createdAt 순으로 소진된다`() {
        val day = LocalDate.of(2026, 6, 10)
        // createdAt: 100원 매수(09시) → 200원 매수(10시) → 매도(11시).
        // 목록은 일부러 뒤섞어 넣는다 — 정렬이 createdAt를 보지 않으면 200원 lot이 먼저 소진된다.
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                tradeCreatedAt(StockTradeType.BUY, "HHH", "10", "200", day, LocalDateTime.of(2026, 6, 10, 10, 0)),
                tradeCreatedAt(StockTradeType.SELL, "HHH", "10", "300", day, LocalDateTime.of(2026, 6, 10, 11, 0)),
                tradeCreatedAt(StockTradeType.BUY, "HHH", "10", "100", day, LocalDateTime.of(2026, 6, 10, 9, 0)),
            ),
            period,
        )
        // FIFO는 09시 lot(100원)을 먼저 소진 → 10*(300-100). 목록순이면 10*(300-200)=1000이 된다.
        assertThat(r["HHH"]).isEqualByComparingTo("2000")
    }

    @Test
    fun `종료일 당일 매도는 그달 실현손익에 들어간다`() {
        val trades = listOf(
            trade(StockTradeType.BUY, "III", "10", "100", LocalDate.of(2026, 6, 10)),
            trade(StockTradeType.SELL, "III", "10", "150", LocalDate.of(2026, 6, 30)), // period.end 당일
        )
        assertThat(FifoRealizedPnlCalculator.calculate(trades, period)["III"])
            .isEqualByComparingTo("500")
        // 그리고 다음 달로 새어 나가지 않는다
        assertThat(FifoRealizedPnlCalculator.calculate(trades, ReportPeriod.monthly(2026, 7))["III"])
            .isEqualByComparingTo("0")
    }

    @Test
    fun `시작일 당일 매도는 전월이 아니라 그달 실현손익에 들어간다`() {
        val trades = listOf(
            trade(StockTradeType.BUY, "JJJ", "10", "100", LocalDate.of(2026, 5, 20)),
            trade(StockTradeType.SELL, "JJJ", "10", "150", LocalDate.of(2026, 6, 1)), // period.start 당일
        )
        assertThat(FifoRealizedPnlCalculator.calculate(trades, period)["JJJ"])
            .isEqualByComparingTo("500")
        // 그리고 전월로 새어 나가지 않는다
        assertThat(FifoRealizedPnlCalculator.calculate(trades, ReportPeriod.monthly(2026, 5))["JJJ"])
            .isEqualByComparingTo("0")
    }
}
