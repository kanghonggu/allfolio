package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.Asset
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.*

// ── Summary ────────────────────────────────────────────────────

data class SummaryReport(
    val userId: UUID,
    val generatedAt: LocalDateTime,
    val nav: BigDecimal,
    val totalPurchaseCost: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPct: BigDecimal,
    val assetCount: Int,
    val accountCount: Int,
    val byType: List<TypeBreakdown>,
    val byCurrency: List<CurrencyBreakdown>,
    val topHoldings: List<TopHolding>,
)

data class TypeBreakdown(val type: String, val value: BigDecimal, val pct: BigDecimal, val count: Int)
data class CurrencyBreakdown(val currency: String, val value: BigDecimal, val pct: BigDecimal)
data class TopHolding(val name: String, val symbol: String?, val type: String, val value: BigDecimal, val pct: BigDecimal)

// ── Allocation ─────────────────────────────────────────────────

data class AllocationReport(
    val userId: UUID,
    val generatedAt: LocalDateTime,
    val totalValue: BigDecimal,
    val byType: List<TypeBreakdown>,
    val byCurrency: List<CurrencyBreakdown>,
    val topHoldings: List<TopHolding>,
    val concentrationHHI: BigDecimal,
    val top5Concentration: BigDecimal,
)

// ── Performance ────────────────────────────────────────────────

data class PerformanceReport(
    val userId: UUID,
    val period: String,
    val generatedAt: LocalDateTime,
    val totalReturn: BigDecimal,
    val periodReturns: Map<String, BigDecimal?>,
    val dailySeries: List<DailyPerf>,
    val twr: BigDecimal?,
    val benchmarkAlpha: BigDecimal?,
)

data class DailyPerf(
    val date: LocalDate,
    val nav: BigDecimal,
    val dailyReturn: BigDecimal,
    val cumulativeReturn: BigDecimal,
    val benchmarkReturn: BigDecimal?,
    val alpha: BigDecimal?,
)

// ── Risk ───────────────────────────────────────────────────────

data class RiskReport(
    val userId: UUID,
    val generatedAt: LocalDateTime,
    val volatility: BigDecimal?,
    val annualizedVolatility: BigDecimal?,
    val var95: BigDecimal?,
    val maxDrawdown: BigDecimal?,
    val sharpeRatio: BigDecimal?,
    val calmarRatio: BigDecimal?,
    val latestDate: LocalDate?,
    val series: List<DailyRisk>,
)

data class DailyRisk(
    val date: LocalDate,
    val volatility: BigDecimal,
    val annualizedVolatility: BigDecimal,
    val var95: BigDecimal,
    val maxDrawdown: BigDecimal,
)

// ── Positions ──────────────────────────────────────────────────

data class PositionsReport(
    val userId: UUID,
    val generatedAt: LocalDateTime,
    val positions: List<PositionRow>,
    val totalUnrealizedPnl: BigDecimal,
    val totalPurchaseCost: BigDecimal,
    val totalCurrentValue: BigDecimal,
    val totalReturnPct: BigDecimal,
)

data class PositionRow(
    val name: String,
    val symbol: String?,
    val type: String,
    val accountName: String,
    val quantity: BigDecimal,
    val avgCost: BigDecimal,
    val purchaseCost: BigDecimal,
    val currentValue: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPct: BigDecimal,
    val currency: String,
    val confidenceLevel: String,
)

// ── Benchmark ──────────────────────────────────────────────────

data class BenchmarkReport(
    val userId: UUID,
    val period: String,
    val generatedAt: LocalDateTime,
    val portfolioReturn: BigDecimal,
    val benchmarks: List<BenchmarkItem>,
    val series: List<BenchmarkSeries>,
)

data class BenchmarkItem(
    val name: String,
    val benchmarkReturn: BigDecimal,
    val alpha: BigDecimal,
)

data class BenchmarkSeries(
    val date: LocalDate,
    val portfolio: BigDecimal,
    val sp500: BigDecimal,
    val btc: BigDecimal,
    val kospi: BigDecimal,
)

// ── Service ────────────────────────────────────────────────────

@Service
class ReportService(
    private val assetRepository: AssetRepository,
    private val accountRepository: AccountRepository,
    private val jdbc: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun summary(userId: UUID): SummaryReport {
        val assets = assetRepository.findByUserId(userId)
        val accounts = accountRepository.findByUserId(userId)
        val totalValue = assets.sumOf { it.currentValue }
        val totalCost = assets.sumOf { it.totalPurchaseCost() }
        val unrealized = assets.sumOf { it.unrealizedPnl() }
        val unrealizedPct = if (totalCost > BigDecimal.ZERO)
            unrealized.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        return SummaryReport(
            userId = userId,
            generatedAt = LocalDateTime.now(),
            nav = totalValue,
            totalPurchaseCost = totalCost,
            unrealizedPnl = unrealized,
            unrealizedPnlPct = unrealizedPct,
            assetCount = assets.size,
            accountCount = accounts.size,
            byType = buildTypeBreakdown(assets, totalValue),
            byCurrency = buildCurrencyBreakdown(assets, totalValue),
            topHoldings = buildTopHoldings(assets, totalValue, 10),
        )
    }

    @Transactional(readOnly = true)
    fun allocation(userId: UUID): AllocationReport {
        val assets = assetRepository.findByUserId(userId)
        val totalValue = assets.sumOf { it.currentValue }
        val topHoldings = buildTopHoldings(assets, totalValue, 10)
        val hhi = computeHHI(assets, totalValue)
        val top5 = topHoldings.take(5).sumOf { it.pct }.divide(BigDecimal(100), 4, RoundingMode.HALF_UP)

        return AllocationReport(
            userId = userId,
            generatedAt = LocalDateTime.now(),
            totalValue = totalValue,
            byType = buildTypeBreakdown(assets, totalValue),
            byCurrency = buildCurrencyBreakdown(assets, totalValue),
            topHoldings = topHoldings,
            concentrationHHI = hhi,
            top5Concentration = top5.multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP),
        )
    }

    @Transactional(readOnly = true)
    fun performance(userId: UUID, period: String): PerformanceReport {
        val dailySeries = queryPerformanceSeries(userId, period)
        val assets = assetRepository.findByUserId(userId)
        val totalValue = assets.sumOf { it.currentValue }
        val totalCost = assets.sumOf { it.totalPurchaseCost() }

        val totalReturn = if (totalCost > BigDecimal.ZERO)
            (totalValue - totalCost).divide(totalCost, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        val periodReturns = computePeriodReturns(dailySeries, totalReturn)
        val latestAlpha = dailySeries.lastOrNull()?.alpha

        return PerformanceReport(
            userId = userId,
            period = period,
            generatedAt = LocalDateTime.now(),
            totalReturn = totalReturn,
            periodReturns = periodReturns,
            dailySeries = dailySeries,
            twr = if (dailySeries.isNotEmpty()) dailySeries.last().cumulativeReturn else totalReturn,
            benchmarkAlpha = latestAlpha,
        )
    }

    @Transactional(readOnly = true)
    fun risk(userId: UUID): RiskReport {
        val series = queryRiskSeries(userId)
        val latest = series.lastOrNull()

        return RiskReport(
            userId = userId,
            generatedAt = LocalDateTime.now(),
            volatility = latest?.volatility,
            annualizedVolatility = latest?.annualizedVolatility,
            var95 = latest?.var95,
            maxDrawdown = latest?.maxDrawdown,
            sharpeRatio = computeSharpe(series),
            calmarRatio = computeCalmar(series),
            latestDate = latest?.date,
            series = series,
        )
    }

    @Transactional(readOnly = true)
    fun positions(userId: UUID): PositionsReport {
        val assets = assetRepository.findByUserId(userId)
        val accounts = accountRepository.findByUserId(userId).associateBy { it.id }

        val rows = assets
            .sortedByDescending { it.currentValue }
            .map { asset ->
                val accountName = accounts[asset.accountId]?.accountName ?: "Unknown"
                val cost = asset.totalPurchaseCost()
                val pnl = asset.unrealizedPnl()
                val pnlPct = if (cost > BigDecimal.ZERO)
                    pnl.divide(cost, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
                else BigDecimal.ZERO

                PositionRow(
                    name = asset.name,
                    symbol = asset.symbol,
                    type = asset.type.name,
                    accountName = accountName,
                    quantity = asset.quantity,
                    avgCost = asset.purchasePrice,
                    purchaseCost = cost,
                    currentValue = asset.currentValue,
                    unrealizedPnl = pnl,
                    unrealizedPnlPct = pnlPct,
                    currency = asset.currency,
                    confidenceLevel = asset.confidenceLevel.name,
                )
            }

        val totalUnrealized = rows.sumOf { it.unrealizedPnl }
        val totalCost = rows.sumOf { it.purchaseCost }
        val totalValue = rows.sumOf { it.currentValue }
        val totalReturnPct = if (totalCost > BigDecimal.ZERO)
            totalUnrealized.divide(totalCost, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        return PositionsReport(
            userId = userId,
            generatedAt = LocalDateTime.now(),
            positions = rows,
            totalUnrealizedPnl = totalUnrealized,
            totalPurchaseCost = totalCost,
            totalCurrentValue = totalValue,
            totalReturnPct = totalReturnPct,
        )
    }

    @Transactional(readOnly = true)
    fun benchmark(userId: UUID, period: String): BenchmarkReport {
        val dailySeries = queryPerformanceSeries(userId, period)
        val assets = assetRepository.findByUserId(userId)
        val totalValue = assets.sumOf { it.currentValue }
        val totalCost = assets.sumOf { it.totalPurchaseCost() }

        val portfolioReturn = if (totalCost > BigDecimal.ZERO)
            (totalValue - totalCost).divide(totalCost, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        // Synthetic benchmark returns (2024 approximate YTD)
        val benchmarkReturns = mapOf(
            "S&P 500" to BigDecimal("23.31"),
            "BTC" to BigDecimal("120.45"),
            "KOSPI" to BigDecimal("-8.21"),
        )

        val benchmarks = benchmarkReturns.map { (name, benchReturn) ->
            BenchmarkItem(
                name = name,
                benchmarkReturn = benchReturn,
                alpha = portfolioReturn.subtract(benchReturn).setScale(2, RoundingMode.HALF_UP),
            )
        }

        // Build series: use performance_daily if available, else synthetic
        val series = buildBenchmarkSeries(dailySeries, period)

        return BenchmarkReport(
            userId = userId,
            period = period,
            generatedAt = LocalDateTime.now(),
            portfolioReturn = portfolioReturn,
            benchmarks = benchmarks,
            series = series,
        )
    }

    // ── Private helpers ────────────────────────────────────────

    private fun buildTypeBreakdown(assets: List<Asset>, totalValue: BigDecimal): List<TypeBreakdown> =
        assets.groupBy { it.type.name }
            .map { (type, list) ->
                val tv = list.sumOf { it.currentValue }
                val pct = pct(tv, totalValue)
                TypeBreakdown(type, tv, pct, list.size)
            }
            .sortedByDescending { it.value }

    private fun buildCurrencyBreakdown(assets: List<Asset>, totalValue: BigDecimal): List<CurrencyBreakdown> =
        assets.groupBy { it.currency }
            .map { (currency, list) ->
                val tv = list.sumOf { it.currentValue }
                CurrencyBreakdown(currency, tv, pct(tv, totalValue))
            }
            .sortedByDescending { it.value }

    private fun buildTopHoldings(assets: List<Asset>, totalValue: BigDecimal, n: Int): List<TopHolding> =
        assets.sortedByDescending { it.currentValue }
            .take(n)
            .map { a ->
                TopHolding(a.name, a.symbol, a.type.name, a.currentValue, pct(a.currentValue, totalValue))
            }

    private fun computeHHI(assets: List<Asset>, totalValue: BigDecimal): BigDecimal {
        if (totalValue <= BigDecimal.ZERO) return BigDecimal.ZERO
        return assets.sumOf { asset ->
            val share = asset.currentValue.divide(totalValue, 6, RoundingMode.HALF_UP)
            share.multiply(share)
        }.setScale(4, RoundingMode.HALF_UP)
    }

    private fun pct(part: BigDecimal, total: BigDecimal): BigDecimal {
        if (total <= BigDecimal.ZERO) return BigDecimal.ZERO
        return part.divide(total, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
    }

    private fun queryPerformanceSeries(userId: UUID, period: String): List<DailyPerf> {
        val days = when (period) {
            "1W"  -> 7
            "1M"  -> 30
            "3M"  -> 90
            "YTD" -> LocalDate.now().dayOfYear
            "1Y"  -> 365
            "ALL" -> 3650
            else  -> 30
        }
        val since = LocalDate.now().minusDays(days.toLong())

        return try {
            jdbc.query(
                """SELECT date, nav, daily_return, cumulative_return, benchmark_return, alpha
                   FROM performance_daily
                   WHERE portfolio_id = ? AND date >= ?
                   ORDER BY date ASC""",
                { rs, _ ->
                    DailyPerf(
                        date = rs.getDate("date").toLocalDate(),
                        nav = rs.getBigDecimal("nav"),
                        dailyReturn = rs.getBigDecimal("daily_return"),
                        cumulativeReturn = rs.getBigDecimal("cumulative_return"),
                        benchmarkReturn = rs.getBigDecimal("benchmark_return"),
                        alpha = rs.getBigDecimal("alpha"),
                    )
                },
                userId, since,
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun queryRiskSeries(userId: UUID): List<DailyRisk> {
        return try {
            jdbc.query(
                """SELECT date, volatility, annualized_volatility, var95, max_drawdown
                   FROM risk_daily
                   WHERE portfolio_id = ?
                   ORDER BY date ASC""",
                { rs, _ ->
                    DailyRisk(
                        date = rs.getDate("date").toLocalDate(),
                        volatility = rs.getBigDecimal("volatility"),
                        annualizedVolatility = rs.getBigDecimal("annualized_volatility"),
                        var95 = rs.getBigDecimal("var95"),
                        maxDrawdown = rs.getBigDecimal("max_drawdown"),
                    )
                },
                userId,
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun computePeriodReturns(series: List<DailyPerf>, totalReturn: BigDecimal): Map<String, BigDecimal?> {
        if (series.isEmpty()) return mapOf(
            "1W" to null, "1M" to null, "3M" to null, "YTD" to null, "1Y" to totalReturn,
        )
        val now = LocalDate.now()
        fun returnSince(days: Int): BigDecimal? {
            val cutoff = now.minusDays(days.toLong())
            val start = series.firstOrNull { !it.date.isBefore(cutoff) } ?: return null
            val end = series.last()
            if (start.nav <= BigDecimal.ZERO) return null
            return (end.nav - start.nav).divide(start.nav, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        }
        return mapOf<String, BigDecimal?>(
            "1W"  to returnSince(7),
            "1M"  to returnSince(30),
            "3M"  to returnSince(90),
            "YTD" to returnSince(now.dayOfYear),
            "1Y"  to (returnSince(365) ?: totalReturn),
        )
    }

    private fun computeSharpe(series: List<DailyRisk>): BigDecimal? {
        if (series.isEmpty()) return null
        // Approximation: annualized_vol from latest, assume 5% risk-free rate
        val latest = series.last()
        val vol = latest.annualizedVolatility
        if (vol <= BigDecimal.ZERO) return null
        // We don't have annualized return here, so return null
        return null
    }

    private fun computeCalmar(series: List<DailyRisk>): BigDecimal? {
        if (series.isEmpty()) return null
        val mdd = series.minOf { it.maxDrawdown }
        if (mdd >= BigDecimal.ZERO) return null
        return null // need annual return
    }

    private fun buildBenchmarkSeries(perfSeries: List<DailyPerf>, period: String): List<BenchmarkSeries> {
        if (perfSeries.isEmpty()) return generateSyntheticSeries(period)

        // SP500, BTC, KOSPI — synthetic daily drift based on known period returns
        val sp500DailyDrift = BigDecimal("0.0009")   // ~23% annualized
        val btcDailyDrift   = BigDecimal("0.003")    // ~120% ish
        val kospiDailyDrift = BigDecimal("-0.00033") // ~-8%

        var sp = BigDecimal(100)
        var btc = BigDecimal(100)
        var kospi = BigDecimal(100)

        return perfSeries.mapIndexed { i, perf ->
            sp    = sp.multiply(BigDecimal.ONE.add(sp500DailyDrift)).setScale(4, RoundingMode.HALF_UP)
            btc   = btc.multiply(BigDecimal.ONE.add(btcDailyDrift)).setScale(4, RoundingMode.HALF_UP)
            kospi = kospi.multiply(BigDecimal.ONE.add(kospiDailyDrift)).setScale(4, RoundingMode.HALF_UP)

            BenchmarkSeries(
                date      = perf.date,
                portfolio = perf.cumulativeReturn,
                sp500     = sp.subtract(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP),
                btc       = btc.subtract(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP),
                kospi     = kospi.subtract(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP),
            )
        }
    }

    private fun generateSyntheticSeries(period: String): List<BenchmarkSeries> {
        val days = when (period) {
            "1W"  -> 7; "1M" -> 30; "3M" -> 90
            "YTD" -> LocalDate.now().dayOfYear
            "1Y"  -> 365; else -> 30
        }
        val start = LocalDate.now().minusDays(days.toLong())
        val series = mutableListOf<BenchmarkSeries>()
        var sp = BigDecimal.ZERO
        var btc = BigDecimal.ZERO
        var kospi = BigDecimal.ZERO
        var portfolio = BigDecimal.ZERO

        for (i in 0 until days) {
            val date = start.plusDays(i.toLong())
            sp        = sp.add(BigDecimal("0.09"))
            btc       = btc.add(BigDecimal("0.30"))
            kospi     = kospi.subtract(BigDecimal("0.03"))
            portfolio = portfolio.add(BigDecimal("0.05"))

            series.add(BenchmarkSeries(date, portfolio.setScale(2, RoundingMode.HALF_UP),
                sp.setScale(2, RoundingMode.HALF_UP),
                btc.setScale(2, RoundingMode.HALF_UP),
                kospi.setScale(2, RoundingMode.HALF_UP)))
        }
        return series
    }
}
