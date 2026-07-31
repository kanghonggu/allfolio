package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MonthlyChangeCalculatorTest {

    private val acct = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6) // 2026-06-01 ~ 2026-06-30

    private fun t(type: StockTradeType, symbol: String, qty: String, price: String, on: LocalDate) =
        StockTrade.create(
            accountId = acct, userId = user, tradeType = type, stockName = "$symbol name", symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal(price),
            totalAmount = BigDecimal(qty).multiply(BigDecimal(price)), tradedAt = on, memo = null,
        )

    @Test
    fun `당월 첫 매수는 신규 편입`() {
        val m = MonthlyChangeCalculator.build(
            listOf(t(StockTradeType.BUY, "AAA", "10", "100", LocalDate.of(2026, 6, 5))),
            period, emptyMap(), mapOf("AAA" to "종목A"),
        )
        assertThat(m.newEntries).hasSize(1)
        assertThat(m.newEntries[0].symbol).isEqualTo("AAA")
        assertThat(m.newEntries[0].firstBuyDate).isEqualTo(LocalDate.of(2026, 6, 5))
        assertThat(m.newEntries[0].buyPrice).isEqualByComparingTo("100")
        assertThat(m.soldOut).isEmpty()
        assertThat(m.qtyChanges).isEmpty()
    }

    @Test
    fun `보유 중 당월 전량매도는 전량 매도이고 실현손익을 붙인다`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "BBB", "10", "100", LocalDate.of(2026, 5, 1)),
                t(StockTradeType.SELL, "BBB", "10", "150", LocalDate.of(2026, 6, 20)),
            ),
            period, mapOf("BBB" to BigDecimal("500")), mapOf("BBB" to "종목B"),
        )
        assertThat(m.soldOut).hasSize(1)
        assertThat(m.soldOut[0].soldOutDate).isEqualTo(LocalDate.of(2026, 6, 20))
        assertThat(m.soldOut[0].realizedPnl).isEqualByComparingTo("500")
        assertThat(m.newEntries).isEmpty()
    }

    @Test
    fun `보유 유지 중 당월 추가매수는 수량 변동`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "CCC", "10", "100", LocalDate.of(2026, 5, 1)),
                t(StockTradeType.BUY, "CCC", "5", "120", LocalDate.of(2026, 6, 10)),
            ),
            period, emptyMap(), mapOf("CCC" to "종목C"),
        )
        assertThat(m.qtyChanges).hasSize(1)
        assertThat(m.qtyChanges[0].netQty).isEqualByComparingTo("5")
        assertThat(m.qtyChanges[0].netBuyAmount).isEqualByComparingTo("600")
        assertThat(m.newEntries).isEmpty()
        assertThat(m.soldOut).isEmpty()
    }

    @Test
    fun `당월 편입 후 전량매도 라운드트립은 전량 매도로 분류`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "DDD", "10", "100", LocalDate.of(2026, 6, 3)),
                t(StockTradeType.SELL, "DDD", "10", "150", LocalDate.of(2026, 6, 25)),
            ),
            period, mapOf("DDD" to BigDecimal("500")), mapOf("DDD" to "종목D"),
        )
        assertThat(m.soldOut).hasSize(1)
        assertThat(m.soldOut[0].soldOutDate).isEqualTo(LocalDate.of(2026, 6, 25))
        assertThat(m.newEntries).isEmpty()
    }

    @Test
    fun `부분 매도는 수량 변동에 음수 증감으로 잡힌다`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "PS", "10", "100", LocalDate.of(2026, 5, 1)),   // 이전월 보유 10
                t(StockTradeType.SELL, "PS", "4", "150", LocalDate.of(2026, 6, 15)),   // 당월 부분매도 4
            ),
            period, emptyMap(), mapOf("PS" to "부분"),
        )
        assertThat(m.qtyChanges).hasSize(1)
        assertThat(m.qtyChanges[0].netQty).isEqualByComparingTo("-4")
        assertThat(m.qtyChanges[0].netBuyAmount).isEqualByComparingTo("-600") // 매수 0 − 매도 600
        assertThat(m.soldOut).isEmpty()
    }

    @Test
    fun `초과 매도는 전량 매도로 분류된다`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "OS", "10", "100", LocalDate.of(2026, 5, 1)),
                t(StockTradeType.SELL, "OS", "15", "150", LocalDate.of(2026, 6, 10)),  // 초과 매도 → qtyEnd<0
            ),
            period, mapOf("OS" to BigDecimal("500")), mapOf("OS" to "초과"),
        )
        assertThat(m.soldOut).hasSize(1)
        assertThat(m.soldOut[0].soldOutDate).isEqualTo(LocalDate.of(2026, 6, 10))
        assertThat(m.qtyChanges).isEmpty()
    }

    @Test
    fun `당월 거래 없으면 변동 없음이고 배당은 무시된다`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "EEE", "10", "100", LocalDate.of(2026, 5, 1)),
                t(StockTradeType.DIVIDEND, "EEE", "0", "0", LocalDate.of(2026, 6, 5)),
            ),
            period, emptyMap(), mapOf("EEE" to "E"),
        )
        assertThat(m.newEntries).isEmpty()
        assertThat(m.soldOut).isEmpty()
        assertThat(m.qtyChanges).isEmpty()
    }
}
