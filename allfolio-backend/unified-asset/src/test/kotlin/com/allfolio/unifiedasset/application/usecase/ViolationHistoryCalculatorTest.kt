package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ViolationHistoryCalculatorTest {

    private val acct = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)

    private fun t(type: StockTradeType, symbol: String, qty: String, on: LocalDate) =
        StockTrade.create(
            accountId = acct, userId = user, tradeType = type, stockName = symbol, symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal.ONE, totalAmount = BigDecimal(qty),
            tradedAt = on, memo = null,
        )

    @Test
    fun `첫 매수는 편입 이벤트와 firstBuyDate를 만든다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("AAA"), listOf(t(StockTradeType.BUY, "AAA", "10", LocalDate.of(2026, 6, 5))),
            emptyMap(), mapOf("AAA" to "종목A"), period,
        )
        assertThat(h.perSymbol["AAA"]!!.firstBuyDate).isEqualTo(LocalDate.of(2026, 6, 5))
        assertThat(h.events).anySatisfy { assertThat(it.event).isEqualTo("편입"); assertThat(it.symbol).isEqualTo("AAA") }
    }

    @Test
    fun `전량 매도는 청산 이벤트를 만든다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("BBB"),
            listOf(t(StockTradeType.BUY, "BBB", "10", LocalDate.of(2026, 6, 3)), t(StockTradeType.SELL, "BBB", "10", LocalDate.of(2026, 6, 20))),
            emptyMap(), mapOf("BBB" to "종목B"), period,
        )
        assertThat(h.events.map { it.event }).containsExactly("편입", "청산")
    }

    @Test
    fun `매수 전량매도 재매수는 편입 청산 편입 3이벤트`() {
        val h = ViolationHistoryCalculator.build(
            setOf("CCC"),
            listOf(
                t(StockTradeType.BUY, "CCC", "5", LocalDate.of(2026, 6, 1)),
                t(StockTradeType.SELL, "CCC", "5", LocalDate.of(2026, 6, 10)),
                t(StockTradeType.BUY, "CCC", "3", LocalDate.of(2026, 6, 20)),
            ),
            emptyMap(), mapOf("CCC" to "종목C"), period,
        )
        assertThat(h.events.map { it.event }).containsExactly("편입", "청산", "편입")
        assertThat(h.perSymbol["CCC"]!!.firstBuyDate).isEqualTo(LocalDate.of(2026, 6, 1))
    }

    @Test
    fun `리스트 등록일이 있으면 리스트등록 이벤트를 만든다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("DDD"), listOf(t(StockTradeType.BUY, "DDD", "10", LocalDate.of(2026, 6, 5))),
            mapOf("DDD" to LocalDate.of(2026, 6, 8)), mapOf("DDD" to "종목D"), period,
        )
        assertThat(h.events).anySatisfy { assertThat(it.event).isEqualTo("리스트등록") }
    }

    @Test
    fun `등록전 보유와 등록후 매수 배지`() {
        val before = ViolationHistoryCalculator.build(
            setOf("E1"), listOf(t(StockTradeType.BUY, "E1", "10", LocalDate.of(2026, 6, 1))),
            mapOf("E1" to LocalDate.of(2026, 6, 10)), mapOf("E1" to "E1"), period,
        )
        assertThat(before.perSymbol["E1"]!!.sinceListed).isEqualTo("등록전보유")
        val after = ViolationHistoryCalculator.build(
            setOf("E2"), listOf(t(StockTradeType.BUY, "E2", "10", LocalDate.of(2026, 6, 20))),
            mapOf("E2" to LocalDate.of(2026, 6, 10)), mapOf("E2" to "E2"), period,
        )
        assertThat(after.perSymbol["E2"]!!.sinceListed).isEqualTo("등록후매수")
    }

    @Test
    fun `프리셋 소스는 프리셋 배지이고 거래없는 심볼은 제외된다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("P1", "NOHOLD"), listOf(t(StockTradeType.BUY, "P1", "10", LocalDate.of(2026, 6, 5))),
            emptyMap(), mapOf("P1" to "P1"), period,
        )
        assertThat(h.perSymbol["P1"]!!.sinceListed).isEqualTo("프리셋")
        assertThat(h.perSymbol).doesNotContainKey("NOHOLD")
    }

    @Test
    fun `같은 날짜 매수 매도 재매수는 시간순 편입 청산 편입 순서를 유지한다`() {
        // 모두 2026-06-05, createdAt 생성순으로 편입→청산→편입. 정렬이 event 문자열이면 청산이 앞서 깨짐.
        val h = ViolationHistoryCalculator.build(
            setOf("SD"),
            listOf(
                t(StockTradeType.BUY, "SD", "10", LocalDate.of(2026, 6, 5)),
                t(StockTradeType.SELL, "SD", "10", LocalDate.of(2026, 6, 5)),
                t(StockTradeType.BUY, "SD", "5", LocalDate.of(2026, 6, 5)),
            ),
            emptyMap(), mapOf("SD" to "SD"), period,
        )
        assertThat(h.events.map { it.event }).containsExactly("편입", "청산", "편입")
    }

    @Test
    fun `배당 미수는 무시되고 기간 이후 거래는 제외된다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("FFF"),
            listOf(
                t(StockTradeType.DIVIDEND, "FFF", "0", LocalDate.of(2026, 6, 5)),
                t(StockTradeType.BUY, "FFF", "10", LocalDate.of(2026, 6, 6)),
                t(StockTradeType.SELL, "FFF", "10", LocalDate.of(2026, 7, 1)),
            ),
            emptyMap(), mapOf("FFF" to "F"), period,
        )
        assertThat(h.events.map { it.event }).containsExactly("편입")
    }
}
