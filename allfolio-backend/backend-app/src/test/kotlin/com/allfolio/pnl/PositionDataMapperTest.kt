package com.allfolio.pnl

import com.allfolio.trade.domain.CostLot
import com.allfolio.trade.domain.LotPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class PositionDataMapperTest {

    private val portfolioId = UUID.randomUUID()
    private val assetId = UUID.randomUUID()

    private fun assertBd(expected: String, actual: BigDecimal) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was $actual")

    @Test
    fun `PositionData with lots maps to LotPosition preserving lot prices`() {
        val data = PositionData(
            portfolioId, assetId, quantity = BigDecimal("15"), avgCost = BigDecimal("150"),
            lots = listOf(PositionLot(BigDecimal("100"), BigDecimal("5")), PositionLot(BigDecimal("200"), BigDecimal("10"))),
        )
        val pos = PositionDataMapper.toLotPosition(data)
        assertBd("15", pos.totalQuantity)
        assertBd("100", pos.fifoCostBasis!!)
    }

    @Test
    fun `legacy lot-less PositionData synthesizes a single lot from avgCost preserving quantity`() {
        val data = PositionData(portfolioId, assetId, quantity = BigDecimal("100"), avgCost = BigDecimal("50"), lots = emptyList())
        val pos = PositionDataMapper.toLotPosition(data)
        assertBd("100", pos.totalQuantity)
        assertBd("50", pos.averageCost)
        assertBd("50", pos.fifoCostBasis!!)
    }

    @Test
    fun `LotPosition maps back to PositionData projecting quantity avgCost and lots`() {
        val pos = LotPosition(listOf(CostLot(BigDecimal("100"), BigDecimal("5")), CostLot(BigDecimal("200"), BigDecimal("10"))), BigDecimal.ZERO)
        val data = PositionDataMapper.toPositionData(pos, portfolioId, assetId, currency = "USDT")
        assertBd("15", data.quantity)
        assertBd("166.6666666667", data.avgCost)
        assertEquals(2, data.lots.size)
        assertEquals("USDT", data.currency)
        assertBd("100", data.lots[0].price)
    }

    @Test
    fun `empty PositionData maps to EMPTY position`() {
        val data = PositionData(portfolioId, assetId, quantity = BigDecimal.ZERO, avgCost = BigDecimal.ZERO, lots = emptyList())
        assertEquals(LotPosition.EMPTY, PositionDataMapper.toLotPosition(data))
    }
}
