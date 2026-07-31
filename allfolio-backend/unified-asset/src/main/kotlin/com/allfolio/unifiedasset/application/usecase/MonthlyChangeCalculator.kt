package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import java.math.BigDecimal
import java.time.LocalDate

data class NewEntry(val symbol: String, val name: String, val firstBuyDate: LocalDate, val buyPrice: BigDecimal)
data class SoldOut(val symbol: String, val name: String, val soldOutDate: LocalDate, val realizedPnl: BigDecimal)
data class QtyChange(val symbol: String, val name: String, val netQty: BigDecimal, val netBuyAmount: BigDecimal)
data class MonthlyChange(val newEntries: List<NewEntry>, val soldOut: List<SoldOut>, val qtyChanges: List<QtyChange>)

/**
 * 거래이력으로 period.start 시점 수량(qtyBefore)·period.end 수량(qtyEnd)을 재구성해 월간 변동 3분류 (순수).
 * 신규편입/전량매도/수량변동. period.end 이후·DIVIDEND/MARGIN 제외. KRW 취급.
 */
object MonthlyChangeCalculator {
    private val BUY = setOf(StockTradeType.BUY, StockTradeType.CREDIT_BUY)
    private val SELL = setOf(StockTradeType.SELL, StockTradeType.CREDIT_SELL)

    fun build(
        trades: List<StockTrade>,
        period: ReportPeriod,
        realizedBySymbol: Map<String, BigDecimal>,
        nameBySymbol: Map<String, String>,
    ): MonthlyChange {
        val newEntries = mutableListOf<NewEntry>()
        val soldOut = mutableListOf<SoldOut>()
        val qtyChanges = mutableListOf<QtyChange>()

        val bySym = trades
            .filter { it.symbol != null && (it.tradeType in BUY || it.tradeType in SELL) }
            .groupBy { it.symbol!! }

        for ((sym, list) in bySym) {
            val upToEnd = list.filter { !it.tradedAt.isAfter(period.end) }
                .sortedWith(compareBy({ it.tradedAt }, { it.createdAt }))
            val periodTrades = upToEnd.filter { !it.tradedAt.isBefore(period.start) }
            if (periodTrades.isEmpty()) continue
            val name = nameBySymbol[sym] ?: sym

            var running = BigDecimal.ZERO
            var qtyBefore = BigDecimal.ZERO
            var firstBuyInPeriod: StockTrade? = null
            var soldOutDate: LocalDate? = null
            for (tr in upToEnd) {
                val inPeriod = !tr.tradedAt.isBefore(period.start)
                if (!inPeriod) {
                    running = apply(running, tr)
                    qtyBefore = running
                    continue
                }
                if (firstBuyInPeriod == null && tr.tradeType in BUY) firstBuyInPeriod = tr
                val before = running
                running = apply(running, tr)
                if (before.signum() > 0 && running.signum() <= 0) soldOutDate = tr.tradedAt
            }
            val qtyEnd = running

            when {
                qtyBefore.signum() <= 0 && qtyEnd.signum() > 0 && firstBuyInPeriod != null ->
                    newEntries += NewEntry(sym, name, firstBuyInPeriod.tradedAt, firstBuyInPeriod.price)

                qtyEnd.signum() <= 0 && (qtyBefore.signum() > 0 || periodTrades.any { it.tradeType in BUY }) ->
                    soldOut += SoldOut(sym, name, soldOutDate ?: periodTrades.last().tradedAt, realizedBySymbol[sym] ?: BigDecimal.ZERO)

                qtyBefore.signum() > 0 && qtyEnd.signum() > 0 && (qtyEnd - qtyBefore).signum() != 0 -> {
                    val netBuy = periodTrades.filter { it.tradeType in BUY }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount } -
                        periodTrades.filter { it.tradeType in SELL }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
                    qtyChanges += QtyChange(sym, name, qtyEnd - qtyBefore, netBuy)
                }
            }
        }

        return MonthlyChange(
            newEntries.sortedBy { it.firstBuyDate },
            soldOut.sortedBy { it.soldOutDate },
            qtyChanges.sortedByDescending { it.netBuyAmount.abs() },
        )
    }

    private fun apply(qty: BigDecimal, t: StockTrade): BigDecimal =
        if (t.tradeType in BUY) qty + t.quantity else qty - t.quantity
}
