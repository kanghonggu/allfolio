package com.allfolio.snapshot.domain

import com.allfolio.trade.domain.TradeRaw
import com.allfolio.trade.domain.TradeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class PositionEngineTest {

    @Test
    fun `buy only calculates quantity average cost and unrealized pnl`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(buy("100", "1000")),
            marketPrice = bd("1200"),
        )

        assertEquals(assetId, snapshot.assetId)
        assertBd("100", snapshot.totalQuantity)
        assertBd("1000", snapshot.averageCost)
        assertBd("0", snapshot.realizedPnl)
        assertBd("20000", snapshot.unrealizedPnl)
        assertBd("20000", snapshot.totalPnl)
        assertTrue(snapshot.hasPosition())
    }

    @Test
    fun `full sell realizes pnl and leaves zero quantity`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(
                buy("100", "1000"),
                sell("100", "1200"),
            ),
            marketPrice = bd("1200"),
        )

        assertBd("0", snapshot.totalQuantity)
        assertBd("0", snapshot.averageCost)
        assertBd("20000", snapshot.realizedPnl)
        assertBd("0", snapshot.unrealizedPnl)
        assertFalse(snapshot.hasPosition())
    }

    @Test
    fun `fifo partial sell consumes part of one lot`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(
                buy("100", "1000"),
                sell("30", "1200"),
            ),
            marketPrice = bd("1200"),
        )

        assertBd("70", snapshot.totalQuantity)
        assertBd("1000", snapshot.averageCost)
        assertBd("6000", snapshot.realizedPnl)
        assertBd("14000", snapshot.unrealizedPnl)
    }

    @Test
    fun `fifo sell spans multiple lots`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(
                buy("100", "1000"),
                buy("50", "1200"),
                sell("120", "1300"),
            ),
            marketPrice = bd("1300"),
        )

        assertBd("30", snapshot.totalQuantity)
        assertBd("1200", snapshot.averageCost)
        assertBd("32000", snapshot.realizedPnl)
        assertBd("3000", snapshot.unrealizedPnl)
    }

    @Test
    fun `sell fee reduces realized pnl`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(
                buy("100", "1000"),
                sell("50", "1200", fee = "500"),
            ),
            marketPrice = bd("1200"),
        )

        assertBd("50", snapshot.totalQuantity)
        assertBd("1000", snapshot.averageCost)
        assertBd("9500", snapshot.realizedPnl)
        assertBd("10000", snapshot.unrealizedPnl)
    }

    @Test
    fun `unrealized pnl can be negative`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(buy("100", "1000")),
            marketPrice = bd("800"),
        )

        assertBd("100", snapshot.totalQuantity)
        assertBd("1000", snapshot.averageCost)
        assertBd("0", snapshot.realizedPnl)
        assertBd("-20000", snapshot.unrealizedPnl)
    }

    @Test
    fun `realized pnl can be negative`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(
                buy("100", "1000"),
                sell("50", "800"),
            ),
            marketPrice = bd("800"),
        )

        assertBd("50", snapshot.totalQuantity)
        assertBd("1000", snapshot.averageCost)
        assertBd("-10000", snapshot.realizedPnl)
        assertBd("-10000", snapshot.unrealizedPnl)
    }

    @Test
    fun `selling more than held throws insufficient quantity`() {
        val exception = assertThrows(PositionException::class.java) {
            PositionEngine.calculate(
                trades = listOf(
                    buy("50", "1000"),
                    sell("100", "1200"),
                ),
                marketPrice = bd("1200"),
            )
        }

        assertEquals("POSITION_INSUFFICIENT_QUANTITY", exception.errorCode)
        assertEquals(
            "Insufficient quantity for asset $assetId: requested=100, available=50",
            exception.message,
        )
    }

    @Test
    fun `empty trades throws empty trades exception`() {
        val exception = assertThrows(PositionException::class.java) {
            PositionEngine.calculate(
                trades = emptyList(),
                marketPrice = bd("1200"),
            )
        }

        assertEquals("POSITION_EMPTY_TRADES", exception.errorCode)
        assertEquals("Trade list must not be empty", exception.message)
    }

    @Test
    fun `buy sell and rebuy calculates average cost from remaining fifo lots`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(
                buy("100", "1000"),
                sell("50", "1100"),
                buy("50", "1300"),
            ),
            marketPrice = bd("1300"),
        )

        assertBd("100", snapshot.totalQuantity)
        assertBd("1150", snapshot.averageCost)
        assertBd("5000", snapshot.realizedPnl)
        assertBd("15000", snapshot.unrealizedPnl)
    }

    @Test
    fun `average cost is rounded to scale 10 half up`() {
        val snapshot = PositionEngine.calculate(
            trades = listOf(
                buy("1", "1000"),
                buy("2", "1001"),
            ),
            marketPrice = bd("1001"),
        )

        assertBd("3", snapshot.totalQuantity)
        assertBd("1000.6666666667", snapshot.averageCost)
        assertEquals(10, snapshot.averageCost.scale())
    }

    private fun buy(
        quantity: String,
        price: String,
        fee: String = "0",
    ): TradeRaw = trade(TradeType.BUY, quantity, price, fee)

    private fun sell(
        quantity: String,
        price: String,
        fee: String = "0",
    ): TradeRaw = trade(TradeType.SELL, quantity, price, fee)

    private fun trade(
        tradeType: TradeType,
        quantity: String,
        price: String,
        fee: String,
    ): TradeRaw = TradeRaw.create(
        portfolioId = portfolioId,
        assetId = assetId,
        tradeType = tradeType,
        quantity = bd(quantity),
        price = bd(price),
        fee = bd(fee),
        tradeCurrency = "KRW",
        executedAt = executedAt,
    )

    private fun assertBd(expected: String, actual: BigDecimal) {
        assertEquals(
            0,
            bd(expected).compareTo(actual),
            "expected ${bd(expected).toPlainString()} but was ${actual.toPlainString()}",
        )
    }

    private fun bd(value: String): BigDecimal = BigDecimal(value)

    private companion object {
        val portfolioId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val assetId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val executedAt: LocalDateTime = LocalDateTime.of(2026, 6, 30, 9, 0)
    }
}
