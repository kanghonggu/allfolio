package com.allfolio.trade.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class FifoCostEngineTest {

    private fun trade(type: TradeType, qty: String, price: String, fee: String = "0") =
        TradeRaw.reconstruct(
            id = TradeId.newId(),
            portfolioId = PORTFOLIO,
            assetId = ASSET,
            tradeType = type,
            quantity = BigDecimal(qty),
            price = BigDecimal(price),
            fee = BigDecimal(fee),
            tradeCurrency = "KRW",
            executedAt = LocalDateTime.now(),
            createdAt = LocalDateTime.now(),
        )

    private fun assertBd(expected: String, actual: BigDecimal) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")

    @Test
    fun `buy accumulates quantity and weighted average cost`() {
        val pos = FifoCostEngine.replay(listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.BUY, "10", "200")))
        assertBd("20", pos.totalQuantity)
        assertBd("150", pos.averageCost)
        assertBd("0", pos.realizedPnl)
        assertBd("100", pos.fifoCostBasis!!)
    }

    @Test
    fun `fifo sell consumes oldest lot first and realizes pnl`() {
        val pos = FifoCostEngine.replay(
            listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.BUY, "10", "200"), trade(TradeType.SELL, "5", "300")),
        )
        assertBd("15", pos.totalQuantity)
        assertBd("166.6666666667", pos.averageCost)
        assertBd("1000", pos.realizedPnl)
        assertBd("100", pos.fifoCostBasis!!)
    }

    @Test
    fun `full sell empties lots and zeroes cost`() {
        val pos = FifoCostEngine.replay(listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.SELL, "10", "150")))
        assertBd("0", pos.totalQuantity)
        assertBd("0", pos.averageCost)
        assertNull(pos.fifoCostBasis)
        assertBd("500", pos.realizedPnl)
    }

    @Test
    fun `sell fee reduces realized pnl`() {
        val pos = FifoCostEngine.replay(listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.SELL, "10", "150", fee = "70")))
        assertBd("430", pos.realizedPnl)
    }

    @Test
    fun `oversell clamps to held quantity without throwing`() {
        val pos = FifoCostEngine.replay(listOf(trade(TradeType.BUY, "10", "100"), trade(TradeType.SELL, "15", "150")))
        assertBd("0", pos.totalQuantity)
        assertBd("500", pos.realizedPnl)
    }

    @Test
    fun `apply incrementally equals replay in batch`() {
        val trades = listOf(
            trade(TradeType.BUY, "10", "100"),
            trade(TradeType.BUY, "5", "200"),
            trade(TradeType.SELL, "8", "300"),
            trade(TradeType.BUY, "3", "150"),
        )
        val incremental = trades.fold(LotPosition.EMPTY) { pos, t ->
            FifoCostEngine.apply(pos, t.tradeType, t.quantity, t.price, t.fee)
        }
        val batch = FifoCostEngine.replay(trades)
        assertEquals(batch, incremental)
    }

    companion object {
        private val PORTFOLIO = UUID.randomUUID()
        private val ASSET = UUID.randomUUID()
    }
}
