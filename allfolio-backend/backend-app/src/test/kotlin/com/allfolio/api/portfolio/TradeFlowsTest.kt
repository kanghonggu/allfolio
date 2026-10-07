package com.allfolio.api.portfolio

import com.allfolio.trade.domain.TradeType
import com.allfolio.trade.infrastructure.entity.TradeRawEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class TradeFlowsTest {

    private val portfolioId = UUID.randomUUID()
    private val krw: (BigDecimal, String) -> BigDecimal = { amount, _ -> amount }

    private fun trade(
        at: LocalDateTime, type: TradeType, qty: String, price: String,
        fee: String = "0", currency: String = "KRW",
    ) = TradeRawEntity(
        id = UUID.randomUUID(), portfolioId = portfolioId, assetId = UUID.randomUUID(),
        tradeType = type, quantity = BigDecimal(qty), price = BigDecimal(price), fee = BigDecimal(fee),
        tradeCurrency = currency, executedAt = at, createdAt = at,
    )

    @Test
    fun `매수는 낸 돈만큼 유입, 매도는 받은 돈만큼 유출 — 수수료는 양쪽 다 포트폴리오 손해 쪽`() {
        val d = LocalDate.of(2026, 9, 1)
        val flows = TradeFlows.of(
            listOf(
                trade(d.atTime(9, 0), TradeType.BUY, "10", "1000", fee = "15"),
                trade(d.plusDays(1).atTime(9, 0), TradeType.SELL, "4", "1200", fee = "10"),
            ),
            krw,
        )

        assertThat(flows.map { it.date }).containsExactly(d, d.plusDays(1))
        assertThat(flows[0].amountKrw).isEqualByComparingTo("10015")   // 10×1000 + 15
        assertThat(flows[1].amountKrw).isEqualByComparingTo("-4790")   // −(4×1200 − 10)
    }

    @Test
    fun `같은 날 거래는 하루 순플로우로 합치고, 날짜는 체결 시각의 날짜다`() {
        val d = LocalDate.of(2026, 9, 1)
        val flows = TradeFlows.of(
            listOf(
                trade(d.atTime(23, 59, 59), TradeType.BUY, "1", "500"),
                trade(d.atTime(0, 0), TradeType.SELL, "1", "200"),
                trade(d.plusDays(1).atTime(0, 0), TradeType.BUY, "1", "7"),
            ),
            krw,
        )

        assertThat(flows.map { it.date to it.amountKrw.toPlainString() })
            .containsExactly(d to "300", d.plusDays(1) to "7")
    }

    @Test
    fun `원통화 금액을 거래 통화로 환산기에 넘긴다`() {
        val d = LocalDate.of(2026, 9, 1)
        val seen = mutableListOf<Pair<String, String>>()
        val flows = TradeFlows.of(
            listOf(trade(d.atTime(9, 0), TradeType.BUY, "2", "50", fee = "1", currency = "USD")),
        ) { amount, currency ->
            seen += amount.toPlainString() to currency
            amount.multiply(BigDecimal("1400"))
        }

        assertThat(seen).containsExactly("101" to "USD")
        assertThat(flows.single().amountKrw).isEqualByComparingTo("141400")
    }
}
