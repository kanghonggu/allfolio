package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.*

// ── Summary ────────────────────────────────────────────────────

data class SummaryReport(
    val userId: UUID,
    val generatedAt: OffsetDateTime,
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
    val generatedAt: OffsetDateTime,
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
    val generatedAt: OffsetDateTime,
    val totalReturn: BigDecimal,
    /** 기간별 TWR(percent). null = 시계열이 해당 기간을 못 덮음 → FE는 '데이터 부족' 표기 (QA P2) */
    val periodReturns: Map<String, BigDecimal?>,
    val dailySeries: List<DailyPerf>,
    val twr: BigDecimal?,
    val benchmarkAlpha: BigDecimal?,
    /** 전체 시계열이 덮는 일수 (첫 관측~마지막 관측) — 기간 버튼 비활성 판단용 */
    val coverageDays: Int,
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
    val generatedAt: OffsetDateTime,
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
    val generatedAt: OffsetDateTime,
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
    /** 표시 통화 통일용 KRW 환산 평가액 (QA P2) — 원통화 값은 currentValue+currency */
    val currentValueKrw: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPct: BigDecimal,
    val currency: String,
    val confidenceLevel: String,
)

// ── Benchmark ──────────────────────────────────────────────────

data class BenchmarkReport(
    val userId: UUID,
    val period: String,
    val generatedAt: OffsetDateTime,
    val portfolioReturn: BigDecimal,
    val benchmarks: List<BenchmarkItem>,
    val series: List<BenchmarkSeries>,
)

data class BenchmarkItem(
    val name: String,
    val benchmarkReturn: BigDecimal,
    val alpha: BigDecimal,
)

/** percent 스케일. 지수 값이 null이면 해당 날짜에 실데이터 없음 (합성값으로 채우지 않는다 — QA P1 #10) */
data class BenchmarkSeries(
    val date: LocalDate,
    val portfolio: BigDecimal,
    val sp500: BigDecimal?,
    val btc: BigDecimal?,
    val kospi: BigDecimal?,
)

// ── NetWorth ───────────────────────────────────────────────────

data class NetWorthBreakdown(
    val type: String,
    val assets: BigDecimal,
    val loan: BigDecimal,
    val netWorth: BigDecimal,
    val pct: BigDecimal,   // netWorth / totalNetWorth * 100
)

data class NetWorthPoint(
    val date: LocalDate,
    val nav: BigDecimal,
)

data class NetWorthReport(
    val userId: UUID,
    val generatedAt: OffsetDateTime,
    val totalAssets: BigDecimal,
    val totalLoan: BigDecimal,
    val netWorth: BigDecimal,
    val byType: List<NetWorthBreakdown>,
    val trend: List<NetWorthPoint>,
)

// ── MonthlyPnl ─────────────────────────────────────────────────

data class MonthlyPnlRow(
    val yearMonth: String,   // "2026-04"
    val startNav: BigDecimal,
    val endNav: BigDecimal,
    val absolutePnl: BigDecimal,   // endNav - startNav
    val returnPct: BigDecimal,     // (endNav - startNav) / startNav * 100, startNav=0이면 0
)

data class MonthlyPnlReport(
    val userId: UUID,
    val generatedAt: OffsetDateTime,
    val months: List<MonthlyPnlRow>,  // 오래된 순서 정렬
    val bestMonth: MonthlyPnlRow?,
    val worstMonth: MonthlyPnlRow?,
    val totalAbsolutePnl: BigDecimal,
    val winMonths: Int,
    val loseMonths: Int,
)

// ── Service ────────────────────────────────────────────────────

@Service
class ReportService(
    private val assetRepository: AssetRepository,
    private val accountRepository: AccountRepository,
    private val jdbc: JdbcTemplate,
    private val fx: FxConverter,
    private val benchmarkStore: com.allfolio.unifiedasset.application.port.BenchmarkDailyStore,
    private val cashFlowRepository: com.allfolio.unifiedasset.application.port.CashFlowRepository,
    // 상위 보유에서 제외할 먼지 포지션 임계값(KRW) — 코인 잔여 단위 등 (QA 후속 #4)
    @org.springframework.beans.factory.annotation.Value("\${allfolio.report.dust-threshold-krw:1000}")
    private val dustThresholdKrw: BigDecimal = BigDecimal(1000),
) {
    @Transactional(readOnly = true)
    fun summary(userId: UUID): SummaryReport {
        val assets = assetRepository.findByUserId(userId)
        val accounts = accountRepository.findByUserId(userId)
        // 크로스-자산 합계는 통화 혼재를 피하려 KRW로 환산해 계산한다.
        val totalValue = assets.navInKrw(fx)
        val totalCost = assets.sumOf { it.purchaseCostInKrw(fx) }
        val unrealized = assets.sumOf { it.unrealizedPnlInKrw(fx) }
        val unrealizedPct = if (totalCost > BigDecimal.ZERO)
            unrealized.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        return SummaryReport(
            userId = userId,
            generatedAt = OffsetDateTime.now(KST),
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
        val totalValue = assets.navInKrw(fx)
        val topHoldings = buildTopHoldings(assets, totalValue, 10)
        val hhi = computeHHI(assets, totalValue)
        val top5 = topHoldings.take(5).sumOf { it.pct }.divide(BigDecimal(100), 4, RoundingMode.HALF_UP)

        return AllocationReport(
            userId = userId,
            generatedAt = OffsetDateTime.now(KST),
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
        val totalValue = assets.navInKrw(fx)
        val totalCost = assets.sumOf { it.purchaseCostInKrw(fx) }

        val totalReturn = if (totalCost > BigDecimal.ZERO)
            (totalValue - totalCost).divide(totalCost, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        // 기간별 수익률은 선택 기간과 무관하게 전체 시계열 + 현금흐름으로 계산 (QA P2)
        val fullSeries = queryPerformanceSeries(userId, "ALL")
        val flows = cashFlowRepository.findByUserId(userId)
            .map { com.allfolio.report.domain.returns.Flow(it.flowDate, it.signedKrw()) }
        val periodReturns = computePeriodReturns(fullSeries, flows)
        val coverageDays = if (fullSeries.isEmpty()) 0
        else java.time.temporal.ChronoUnit.DAYS
            .between(fullSeries.first().date, fullSeries.last().date).toInt() + 1
        val latestAlpha = dailySeries.lastOrNull()?.alpha

        return PerformanceReport(
            userId = userId,
            period = period,
            generatedAt = OffsetDateTime.now(KST),
            totalReturn = totalReturn,
            periodReturns = periodReturns,
            dailySeries = dailySeries,
            coverageDays = coverageDays,
            // cumulative_return은 ratio(0~1) 저장 — 응답은 기간 카드(totalReturn 등)와 동일한 percent (QA P1 #7)
            twr = if (dailySeries.isNotEmpty())
                dailySeries.last().cumulativeReturn.multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
            else totalReturn,
            benchmarkAlpha = latestAlpha,
        )
    }

    @Transactional(readOnly = true)
    fun risk(userId: UUID): RiskReport {
        val series = queryRiskSeries(userId)
        val latest = series.lastOrNull()

        return RiskReport(
            userId = userId,
            generatedAt = OffsetDateTime.now(KST),
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
            .sortedByDescending { it.currentValueInKrw(fx) }
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
                    currentValueKrw = asset.currentValueInKrw(fx).setScale(0, RoundingMode.HALF_UP),
                    unrealizedPnl = pnl,
                    unrealizedPnlPct = pnlPct,
                    currency = asset.currency,
                    confidenceLevel = asset.confidenceLevel.name,
                )
            }

        // 개별 포지션(rows)은 원래 통화로 표시하되, 합계는 KRW 환산 기준으로 집계한다.
        val totalUnrealized = assets.sumOf { it.unrealizedPnlInKrw(fx) }
        val totalCost = assets.sumOf { it.purchaseCostInKrw(fx) }
        val totalValue = assets.navInKrw(fx)
        val totalReturnPct = if (totalCost > BigDecimal.ZERO)
            totalUnrealized.divide(totalCost, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        return PositionsReport(
            userId = userId,
            generatedAt = OffsetDateTime.now(KST),
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
        val totalValue = assets.navInKrw(fx)
        val totalCost = assets.sumOf { it.purchaseCostInKrw(fx) }

        val portfolioReturn = if (totalCost > BigDecimal.ZERO)
            (totalValue - totalCost).divide(totalCost, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        // 실제 지수 시계열(benchmark_daily, 일일 sync) 기반 — 데이터 없으면 목록에서 제외 (QA P1 #10)
        val today = LocalDate.now()
        val since = today.minusDays(periodDays(period).toLong())
        val indexSeries = com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.entries.associateWith { type ->
            // 휴장일 대비 앵커 여유 2주 — 기간 시작 이전 마지막 종가를 기저로 쓴다
            benchmarkStore.series(type, since.minusDays(14), today)
        }

        val benchmarks = indexSeries.mapNotNull { (type, rows) ->
            val ret = indexPeriodReturn(rows, since) ?: return@mapNotNull null
            BenchmarkItem(
                name = type.label,
                benchmarkReturn = ret,
                alpha = portfolioReturn.subtract(ret).setScale(2, RoundingMode.HALF_UP),
            )
        }

        val series = buildBenchmarkSeries(dailySeries, indexSeries, since)

        return BenchmarkReport(
            userId = userId,
            period = period,
            generatedAt = OffsetDateTime.now(KST),
            portfolioReturn = portfolioReturn,
            benchmarks = benchmarks,
            series = series,
        )
    }

    @Transactional(readOnly = true)
    fun networth(userId: UUID): NetWorthReport {
        val assets = assetRepository.findByUserId(userId)
        val totalAssets = assets.navInKrw(fx)
        val totalLoan = assets.sumOf { it.loanAmountInKrw(fx) }
        val netWorth = totalAssets - totalLoan

        val byType = assets.groupBy { it.type.name }
            .map { (type, list) ->
                val typeAssets = list.navInKrw(fx)
                val typeLoan = list.sumOf { it.loanAmountInKrw(fx) }
                val typeNetWorth = typeAssets - typeLoan
                val typePct = if (netWorth != BigDecimal.ZERO)
                    typeNetWorth.divide(netWorth, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
                else BigDecimal.ZERO
                NetWorthBreakdown(
                    type = type,
                    assets = typeAssets,
                    loan = typeLoan,
                    netWorth = typeNetWorth,
                    pct = typePct,
                )
            }
            .sortedByDescending { it.netWorth }

        val since = LocalDate.now().minusDays(365)
        val trend = try {
            jdbc.query(
                """SELECT date, nav FROM performance_daily WHERE portfolio_id = ? AND date >= ? ORDER BY date ASC""",
                { rs, _ ->
                    NetWorthPoint(
                        date = rs.getDate("date").toLocalDate(),
                        nav = rs.getBigDecimal("nav"),
                    )
                },
                userId, since,
            )
        } catch (e: Exception) {
            emptyList()
        }

        return NetWorthReport(
            userId = userId,
            generatedAt = OffsetDateTime.now(KST),
            totalAssets = totalAssets,
            totalLoan = totalLoan,
            netWorth = netWorth,
            byType = byType,
            trend = trend,
        )
    }

    @Transactional(readOnly = true)
    fun monthlyPnl(userId: UUID): MonthlyPnlReport {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM")

        val allPoints = try {
            jdbc.query(
                """SELECT date, nav FROM performance_daily WHERE portfolio_id = ? ORDER BY date ASC""",
                { rs, _ ->
                    NetWorthPoint(
                        date = rs.getDate("date").toLocalDate(),
                        nav = rs.getBigDecimal("nav"),
                    )
                },
                userId,
            )
        } catch (e: Exception) {
            emptyList()
        }

        val grouped = allPoints.groupBy { it.date.format(fmt) }
            .toSortedMap()

        val sortedMonths = grouped.keys.toList()

        val rows = mutableListOf<MonthlyPnlRow>()
        sortedMonths.forEachIndexed { idx, yearMonth ->
            val monthPoints = grouped[yearMonth] ?: return@forEachIndexed
            val endNav = monthPoints.last().nav

            val startNav = if (idx == 0) {
                monthPoints.first().nav
            } else {
                val prevMonth = sortedMonths[idx - 1]
                grouped[prevMonth]?.last()?.nav ?: monthPoints.first().nav
            }

            val absolutePnl = endNav - startNav
            val returnPct = if (startNav != BigDecimal.ZERO)
                absolutePnl.divide(startNav, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
            else BigDecimal.ZERO

            rows.add(MonthlyPnlRow(
                yearMonth = yearMonth,
                startNav = startNav,
                endNav = endNav,
                absolutePnl = absolutePnl,
                returnPct = returnPct,
            ))
        }

        val bestMonth = rows.maxByOrNull { it.returnPct }
        val worstMonth = rows.minByOrNull { it.returnPct }
        val totalAbsolutePnl = rows.sumOf { it.absolutePnl }
        val winMonths = rows.count { it.returnPct > BigDecimal.ZERO }
        val loseMonths = rows.count { it.returnPct < BigDecimal.ZERO }

        return MonthlyPnlReport(
            userId = userId,
            generatedAt = OffsetDateTime.now(KST),
            months = rows,
            bestMonth = bestMonth,
            worstMonth = worstMonth,
            totalAbsolutePnl = totalAbsolutePnl,
            winMonths = winMonths,
            loseMonths = loseMonths,
        )
    }

    // ── Private helpers ────────────────────────────────────────

    // 아래 집계 헬퍼들은 통화 혼재 왜곡을 피하려 자산 가치를 KRW로 환산해 계산한다.
    private fun buildTypeBreakdown(assets: List<Asset>, totalValue: BigDecimal): List<TypeBreakdown> =
        assets.groupBy { it.type.name }
            .map { (type, list) ->
                val tv = list.navInKrw(fx)
                val pct = pct(tv, totalValue)
                TypeBreakdown(type, tv, pct, list.size)
            }
            .sortedByDescending { it.value }

    // 통화별 익스포저: 각 통화 버킷의 KRW 환산 합계를 표시한다.
    private fun buildCurrencyBreakdown(assets: List<Asset>, totalValue: BigDecimal): List<CurrencyBreakdown> =
        assets.groupBy { it.currency }
            .map { (currency, list) ->
                val tv = list.navInKrw(fx)
                CurrencyBreakdown(currency, tv, pct(tv, totalValue))
            }
            .sortedByDescending { it.value }

    // 임계값(KRW) 미만 먼지 포지션(FDUSD 18원 등)은 상위 보유 목록에서 제외 (QA 후속 #4)
    private fun buildTopHoldings(assets: List<Asset>, totalValue: BigDecimal, n: Int): List<TopHolding> =
        assets.map { a -> a to a.currentValueInKrw(fx) }
            .filter { (_, vKrw) -> vKrw >= dustThresholdKrw }
            .sortedByDescending { (_, vKrw) -> vKrw }
            .take(n)
            .map { (a, vKrw) -> TopHolding(a.name, a.symbol, a.type.name, vKrw, pct(vKrw, totalValue)) }

    private fun computeHHI(assets: List<Asset>, totalValue: BigDecimal): BigDecimal {
        if (totalValue <= BigDecimal.ZERO) return BigDecimal.ZERO
        return assets.sumOf { asset ->
            val share = asset.currentValueInKrw(fx).divide(totalValue, 6, RoundingMode.HALF_UP)
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

    /**
     * 기간별 수익률 (QA P2) — flow-aware TWR로 통일(대시보드와 동일 엔진).
     * 시계열이 요청 기간을 못 덮으면(윈도 중간 시작) 왜곡된 수치 대신 null을 내려
     * FE가 '데이터 부족'으로 표기하게 한다 — 모든 기간이 같은 값(+2060%)을 반환하던 버그 제거.
     */
    private fun computePeriodReturns(
        series: List<DailyPerf>,
        flows: List<com.allfolio.report.domain.returns.Flow>,
    ): Map<String, BigDecimal?> {
        val empty = mapOf<String, BigDecimal?>("1W" to null, "1M" to null, "3M" to null, "YTD" to null, "1Y" to null)
        if (series.size < 2) return empty
        val now = LocalDate.now()
        val navPoints = series.map { com.allfolio.report.domain.returns.NavPoint(it.date, it.nav) }

        // 대시보드(GetDashboardUseCase)와 동일한 공용 엔진 — 두 엔드포인트 수치 불일치 방지 (QA 후속 #3)
        fun twrSince(cutoff: LocalDate): BigDecimal? =
            com.allfolio.report.domain.returns.ReturnsCalculator
                .periodTwrPercent(navPoints, flows, cutoff, now)
        return mapOf(
            "1W"  to twrSince(now.minusDays(7)),
            "1M"  to twrSince(now.minusDays(30)),
            "3M"  to twrSince(now.minusDays(90)),
            "YTD" to twrSince(LocalDate.of(now.year, 1, 1)),
            "1Y"  to twrSince(now.minusDays(365)),
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

    private fun periodDays(period: String): Int = when (period) {
        "1W"  -> 7; "1M" -> 30; "3M" -> 90
        "YTD" -> LocalDate.now().dayOfYear
        "1Y"  -> 365; "ALL" -> 3650
        else  -> 30
    }

    /** 기간 시작 이전 마지막 종가 대비 최종 종가 수익률(percent). 데이터 2건 미만이면 null */
    private fun indexPeriodReturn(rows: List<Pair<LocalDate, BigDecimal>>, since: LocalDate): BigDecimal? {
        if (rows.size < 2) return null
        val base = (rows.lastOrNull { it.first <= since } ?: rows.first()).second
        val last = rows.last().second
        if (base <= BigDecimal.ZERO) return null
        return last.subtract(base).divide(base, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * 포트폴리오 percent(cumulative_return ratio × 100)와 지수 정규화 percent를 날짜별로 결합.
     * 지수 실데이터가 없는 날은 null — 합성값으로 채우지 않는다 (QA P1 #10).
     */
    private fun buildBenchmarkSeries(
        perfSeries: List<DailyPerf>,
        indexSeries: Map<com.allfolio.unifiedasset.domain.benchmark.BenchmarkType, List<Pair<LocalDate, BigDecimal>>>,
        since: LocalDate,
    ): List<BenchmarkSeries> {
        if (perfSeries.isEmpty()) return emptyList()

        fun indexPctAt(type: com.allfolio.unifiedasset.domain.benchmark.BenchmarkType, date: LocalDate): BigDecimal? {
            val rows = indexSeries[type].orEmpty()
            if (rows.size < 2) return null
            val base = (rows.lastOrNull { it.first <= since } ?: rows.first()).second
            if (base <= BigDecimal.ZERO) return null
            val close = rows.lastOrNull { it.first <= date }?.second ?: return null
            return close.subtract(base).divide(base, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        }

        return perfSeries.map { perf ->
            BenchmarkSeries(
                date      = perf.date,
                portfolio = perf.cumulativeReturn.multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP),
                sp500     = indexPctAt(com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.SPX, perf.date),
                btc       = indexPctAt(com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.BTC, perf.date),
                kospi     = indexPctAt(com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.KOSPI, perf.date),
            )
        }
    }

    companion object {
        /**
         * `generatedAt`을 찍는 시계. **서버 기본 타임존을 쓰면 안 된다** — Render 컨테이너에
         * TZ 설정이 없어 벽시계가 UTC다.
         *
         * 이 필드는 `LocalDateTime`이었다. Jackson은 오프셋 없이 적고(`"2026-08-15T11:49:00"`),
         * 브라우저의 `new Date(...)`는 오프셋 없는 값을 **읽는 쪽 로컬 시각**으로 해석한다.
         * 그래서 한국 사용자는 20:49에 11:49를 봤다 — 새벽만이 아니라 하루 종일 9시간씩.
         *
         * 값만 KST로 옮기는 것으로는 부족해서 타입을 [OffsetDateTime]으로 바꿨다. 값만 옮기면
         * 한국 사용자에겐 맞지만 다른 시간대 사용자에겐 여전히 조용히 틀린다 — 전선이 오프셋을
         * 안 실으니 읽는 쪽이 계속 추측한다. 오프셋을 실으면 누가 읽든 같은 순간이 된다.
         *
         * KST로 찍는 건 정확성이 아니라 가독성 때문이다 — 원시 JSON을 눈으로 볼 때 한국 시각으로
         * 읽힌다. 절대 시각은 어느 존으로 찍든 같다.
         */
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
