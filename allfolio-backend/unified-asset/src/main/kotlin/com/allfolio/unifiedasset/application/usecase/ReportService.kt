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

/**
 * performance_daily 한 행. DB에서 읽을 땐 저장값(비율 0~1, 현금흐름 미조정)이지만,
 * [ReportService.performance] 응답으로 나갈 땐 수익률 두 칸을 현금흐름 조정 percent로 바꿔 싣는다.
 */
data class DailyPerf(
    val date: LocalDate,
    val nav: BigDecimal,
    /** 응답: 그날로 끝나는 구간 수익률(percent). 구간이 없는 날(첫 관측·분모 ≤ 0)은 null */
    val dailyReturn: BigDecimal?,
    /** 응답: 선택 기간 시작부터 그날까지의 TWR(percent). 스냅샷이 기간 시작을 못 덮으면 null */
    val cumulativeReturn: BigDecimal?,
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
    /** 설정 이후 연환산 기준 — 위 30일 지표와 창이 다르다. 근거는 [RiskAdjustedRatios] */
    val sharpeRatio: BigDecimal?,
    val calmarRatio: BigDecimal?,
    /** 샤프·칼마 창(설정 이후)의 MDD. 구간 수익률이 모자라면 null — 화면이 "낙폭 없음"과 "데이터 부족"을 가른다 */
    val ratioMaxDrawdown: BigDecimal?,
    /** 샤프에 쓴 무위험 수익률(연 %, CD 91일). 수집값이 없으면 null이고 샤프도 null */
    val riskFreeRate: BigDecimal?,
    val riskFreeRateDate: LocalDate?,
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
    /** 선택 기간의 TWR(percent). **시계열이 기간을 못 덮으면 null** — 숫자를 만들지 않는다 */
    val portfolioReturn: BigDecimal?,
    val benchmarks: List<BenchmarkItem>,
    val series: List<BenchmarkSeries>,
)

data class BenchmarkItem(
    val name: String,
    /** 사용자가 이 지수에 해당하는 시장을 들고 있는가. FE가 칩 기본값으로 쓴다 (AF-107) */
    val held: Boolean,
    val benchmarkReturn: BigDecimal,
    /** 포트폴리오 쪽 기저가 없으면 null */
    val alpha: BigDecimal?,
)

/** percent 스케일. 지수 값이 null이면 해당 날짜에 실데이터 없음 (합성값으로 채우지 않는다 — QA P1 #10) */
data class BenchmarkSeries(
    val date: LocalDate,
    /** 기간 시작(앵커)부터 그날까지의 TWR(percent). 스냅샷이 기간 시작을 못 덮으면 null — portfolioReturn과 같다 */
    val portfolio: BigDecimal?,
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
    private val riskFreeRateSource: com.allfolio.unifiedasset.application.port.RiskFreeRateSource,
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
        // 🔴 누적선·알파는 같은 화면 기간 카드와 같은 시작점을 쓴다 — 선의 끝점이 periodReturns[period]다.
        val now = LocalDate.now(KST)
        val cutoff = periodCutoff(period, now, fullSeries)
        val cumulativeAt = portfolioTwrLine(fullSeries, flows, cutoff)
        val dailyAt = dailyReturnPercentByDate(fullSeries, flows)
        val responseSeries = dailySeries.map {
            it.copy(dailyReturn = dailyAt[it.date], cumulativeReturn = cumulativeAt(it.date))
        }

        // 저장 alpha는 통합자산 스냅샷이 쓰지 않아 항상 null이었다(카드가 한 번도 안 떴다).
        // benchmark()의 알파와 같은 정의: 같은 창의 포트폴리오 TWR − KOSPI 수익률.
        val benchmarkAlpha = periodReturns[period]?.let { portfolio ->
            val kospi = indexPeriodReturn(
                benchmarkStore.series(com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.KOSPI, cutoff.minusDays(14), now),
                cutoff,
            )
            kospi?.let { portfolio.subtract(it).setScale(2, RoundingMode.HALF_UP) }
        }

        return PerformanceReport(
            userId = userId,
            period = period,
            generatedAt = OffsetDateTime.now(KST),
            totalReturn = totalReturn,
            periodReturns = periodReturns,
            dailySeries = responseSeries,
            coverageDays = coverageDays,
            twr = sinceInceptionTwrPercent(fullSeries, flows),
            benchmarkAlpha = benchmarkAlpha,
        )
    }

    @Transactional(readOnly = true)
    fun risk(userId: UUID): RiskReport {
        // risk_daily가 아니라 NAV + 외부 플로우에서 다시 계산한다 — 입금이 수익으로, 출금이
        // 손실로 잡히던 문제(daily_return 미조정). 근거는 FlowAdjustedRiskSeries KDoc.
        val flows = cashFlowRepository.findByUserId(userId)
            .map { com.allfolio.report.domain.returns.Flow(it.flowDate, it.signedKrw()) }
        val navSeries = queryNavSeries(userId)
        val series = FlowAdjustedRiskSeries.build(navSeries, flows)
        val latest = series.lastOrNull()
        val riskFree = riskFreeRateSource.latest(LocalDate.now(KST))
        val ratios = RiskAdjustedRatios.compute(navSeries, flows, riskFree?.ratePct)

        return RiskReport(
            userId = userId,
            generatedAt = OffsetDateTime.now(KST),
            volatility = latest?.volatility,
            annualizedVolatility = latest?.annualizedVolatility,
            var95 = latest?.var95,
            maxDrawdown = latest?.maxDrawdown,
            sharpeRatio = ratios?.sharpe,
            calmarRatio = ratios?.calmar,
            ratioMaxDrawdown = ratios?.maxDrawdown,
            riskFreeRate = riskFree?.ratePct,
            riskFreeRateDate = riskFree?.quoteDate,
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

        // 실제 지수 시계열(benchmark_daily, 일일 sync) 기반 — 데이터 없으면 목록에서 제외 (QA P1 #10)
        val today = LocalDate.now(KST)
        val since = today.minusDays(periodDays(period).toLong())

        // 🔴 **알파는 두 수가 같은 창일 때만 뜻이 있다** (AF-107).
        //
        // 여기 있던 것은 보유 자산의 `(평가액 − 취득가) / 취득가`였다 — **기간을 안 본다.**
        // 지수 쪽은 `since` 기준인데 포트폴리오 쪽은 취득 이래 전체라, 기간을 바꾸면
        // 지수만 움직이고 그 차를 알파라고 불렀다. 1W를 골라도 1Y를 골라도 같은 숫자였다.
        //
        // 같은 `since`로 TWR을 낸다. 앵커(기간 시작 이전 마지막 관측)를 잡아야 하므로
        // 창이 아니라 **전 구간**을 읽는다 — performance()가 기간 카드에 쓰는 것과 같은 엔진이다.
        val fullSeries = queryPerformanceSeries(userId, "ALL")
        val flows = cashFlowRepository.findByUserId(userId)
            .map { com.allfolio.report.domain.returns.Flow(it.flowDate, it.signedKrw()) }
        val portfolioReturn = com.allfolio.report.domain.returns.ReturnsCalculator.periodTwrPercent(
            fullSeries.map { com.allfolio.report.domain.returns.NavPoint(it.date, it.nav) },
            flows,
            since,
            today,
        )
        val indexSeries = com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.entries.associateWith { type ->
            // 휴장일 대비 앵커 여유 2주 — 기간 시작 이전 마지막 종가를 기저로 쓴다
            benchmarkStore.series(type, since.minusDays(14), today)
        }

        // 보유 시장 판정 (AF-107) — *"국내주식만 가진 사용자에게 항셍은 소음이다"*.
        // 숨기는 게 아니라 **기본으로 켜 둘 것**을 고르는 데 쓴다. 매핑이 틀렸을 때
        // 사용자가 보고 싶은 지수를 영영 못 보는 쪽보다, 칩 하나가 꺼진 채 남는 쪽이 낫다.
        val held = heldMarkets(assetRepository.findByUserId(userId))

        val benchmarks = indexSeries.mapNotNull { (type, rows) ->
            val ret = indexPeriodReturn(rows, since) ?: return@mapNotNull null
            BenchmarkItem(
                held = type in held,
                name = type.label,
                benchmarkReturn = ret,
                // 기저가 없으면 알파도 없다. 취득가 기준 수익률로 조용히 갈아타면
                // 화면은 그게 TWR인 줄 알고 지수와 나란히 놓는다 — 고치려던 그 상태다.
                alpha = portfolioReturn?.subtract(ret)?.setScale(2, RoundingMode.HALF_UP),
            )
        }

        val series = buildBenchmarkSeries(dailySeries, fullSeries, flows, indexSeries, since)

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

        val since = LocalDate.now(KST).minusDays(365)
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
            "YTD" -> LocalDate.now(KST).dayOfYear
            "1Y"  -> 365
            "ALL" -> 3650
            else  -> 30
        }
        val since = LocalDate.now(KST).minusDays(days.toLong())

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

    private fun queryNavSeries(userId: UUID): List<com.allfolio.report.domain.returns.NavPoint> {
        return try {
            jdbc.query(
                """SELECT date, nav
                   FROM performance_daily
                   WHERE portfolio_id = ?
                   ORDER BY date ASC""",
                { rs, _ ->
                    com.allfolio.report.domain.returns.NavPoint(
                        date = rs.getDate("date").toLocalDate(),
                        nav = rs.getBigDecimal("nav"),
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
    /**
     * 첫 관측일부터 마지막 관측일까지의 TWR(percent) — 화면의 "전체 수익률" 아래 "TWR" 줄.
     *
     * 저장된 `cumulative_return`을 쓰지 않는다. PerformanceSnapshotService가 그 값을
     * `(NAV − 최초 NAV) / 최초 NAV`로 써서 입금이 통째로 수익, 출금이 손실로 잡힌다(실측 +2060%류).
     * 기간 카드([computePeriodReturns])와 같은 엔진·같은 현금흐름으로 체인링킹한다.
     *
     * 관측이 2건 미만이면 null(FE는 줄을 숨긴다). 예전엔 이때 매입 원가 기준 totalReturn을
     * 넣었는데, 그건 TWR이 아니라 "TWR:" 라벨 아래 다른 지표가 나가는 것이었다.
     * percent 스케일은 기간 카드와 같다(QA P1 #7).
     */
    private fun sinceInceptionTwrPercent(
        series: List<DailyPerf>,
        flows: List<com.allfolio.report.domain.returns.Flow>,
    ): BigDecimal? {
        if (series.size < 2) return null
        val navPoints = series.map { com.allfolio.report.domain.returns.NavPoint(it.date, it.nav) }
        return com.allfolio.report.domain.returns.ReturnsCalculator
            .calculate(navPoints, flows, series.first().date, series.last().date)
            .twr
            ?.multiply(BigDecimal(100))
            ?.setScale(2, RoundingMode.HALF_UP)
    }

    private fun computePeriodReturns(
        series: List<DailyPerf>,
        flows: List<com.allfolio.report.domain.returns.Flow>,
    ): Map<String, BigDecimal?> {
        val empty = mapOf<String, BigDecimal?>("1W" to null, "1M" to null, "3M" to null, "YTD" to null, "1Y" to null)
        if (series.size < 2) return empty
        val now = LocalDate.now(KST)
        val navPoints = series.map { com.allfolio.report.domain.returns.NavPoint(it.date, it.nav) }

        // 대시보드(GetDashboardUseCase)와 동일한 공용 엔진 — 두 엔드포인트 수치 불일치 방지 (QA 후속 #3)
        fun twrSince(cutoff: LocalDate): BigDecimal? =
            com.allfolio.report.domain.returns.ReturnsCalculator
                .periodTwrPercent(navPoints, flows, cutoff, now)
        return CARD_PERIODS.associateWith { twrSince(periodCutoff(it, now, series)) }
    }

    /**
     * 성과 화면의 기간 → 시작일. 기간 카드·누적선·알파가 **이 하나**를 같이 쓴다 — 갈라지면 선의 끝점이 카드와 어긋난다.
     *
     * ALL은 카드에 없지만 API로 올 수 있다. 시작일을 첫 관측일로 둬서 전체 기간 선이 된다.
     * 그 밖의 값은 queryPerformanceSeries의 기본값(30일)과 맞춘다.
     */
    private fun periodCutoff(period: String, now: LocalDate, fullSeries: List<DailyPerf>): LocalDate = when (period) {
        "1W"  -> now.minusDays(7)
        "1M"  -> now.minusDays(30)
        "3M"  -> now.minusDays(90)
        "YTD" -> LocalDate.of(now.year, 1, 1)
        "1Y"  -> now.minusDays(365)
        "ALL" -> fullSeries.minOfOrNull { it.date } ?: now
        else  -> now.minusDays(30)
    }

    /** 날짜 → 그날로 끝나는 구간 수익률(percent). 저장 daily_return은 (NAV − 전일 NAV)/전일 NAV라 입금일이 수익이다 */
    private fun dailyReturnPercentByDate(
        fullSeries: List<DailyPerf>,
        flows: List<com.allfolio.report.domain.returns.Flow>,
    ): Map<LocalDate, BigDecimal> =
        com.allfolio.report.domain.returns.ReturnsCalculator
            .segmentReturns(fullSeries.map { com.allfolio.report.domain.returns.NavPoint(it.date, it.nav) }, flows)
            .associate { it.date to it.ratio.multiply(BigDecimal(100)).setScale(DAILY_PCT_SCALE, RoundingMode.HALF_UP) }

    private fun periodDays(period: String): Int = when (period) {
        "1W"  -> 7; "1M" -> 30; "3M" -> 90
        "YTD" -> LocalDate.now(KST).dayOfYear
        "1Y"  -> 365; "ALL" -> 3650
        else  -> 30
    }

    /**
     * 보유 자산에서 "이 사람과 상관있는 지수"를 고른다 (AF-107).
     *
     * | 지수 | 조건 |
     * | --- | --- |
     * | KOSPI | 통화가 KRW인 주식 |
     * | S&P 500 | 통화가 KRW가 아닌 주식 |
     * | Bitcoin | 암호화폐 |
     *
     * **통화로 가르는 것은 근사다.** 원화로 환산해 담은 해외주식이나 KRW 표시 해외 ETF는
     * KOSPI 쪽으로 잡힌다. 그래서 이 판정이 지수를 **숨기지 않는다** — 칩 기본값만 정한다.
     * 정확히 가르려면 종목의 상장 시장이 필요한데 `ua_assets`에 그 컬럼이 없다.
     */
    private fun heldMarkets(assets: List<com.allfolio.unifiedasset.domain.asset.Asset>):
        Set<com.allfolio.unifiedasset.domain.benchmark.BenchmarkType> {
        return assets.mapNotNullTo(mutableSetOf()) { a ->
            when {
                a.type == com.allfolio.unifiedasset.domain.asset.AssetType.CRYPTO ->
                    com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.BTC
                a.type == com.allfolio.unifiedasset.domain.asset.AssetType.STOCK &&
                    a.currency.equals("KRW", ignoreCase = true) ->
                    com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.KOSPI
                a.type == com.allfolio.unifiedasset.domain.asset.AssetType.STOCK ->
                    com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.SPX
                else -> null
            }
        }
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
        fullSeries: List<DailyPerf>,
        flows: List<com.allfolio.report.domain.returns.Flow>,
        indexSeries: Map<com.allfolio.unifiedasset.domain.benchmark.BenchmarkType, List<Pair<LocalDate, BigDecimal>>>,
        since: LocalDate,
    ): List<BenchmarkSeries> {
        if (perfSeries.isEmpty()) return emptyList()
        val portfolioPctAt = portfolioTwrLine(fullSeries, flows, since)

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
                portfolio = portfolioPctAt(perf.date),
                sp500     = indexPctAt(com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.SPX, perf.date),
                btc       = indexPctAt(com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.BTC, perf.date),
                kospi     = indexPctAt(com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.KOSPI, perf.date),
            )
        }
    }

    /**
     * 기간 시작부터의 포트폴리오 TWR 선 — 날짜 → 그날까지의 TWR(percent).
     * 벤치마크 차트의 포트폴리오 선과 성과 화면의 누적 수익률 차트가 같이 쓴다.
     *
     * 저장 `cumulative_return`을 쓰지 않는다. 그 값은 `(NAV − 최초 NAV) / 최초 NAV`라 **입금일에 선이 튀었고**,
     * 기준점도 첫 스냅샷이라 같은 차트의 지수 선(기간 시작 기준)·헤드라인 [BenchmarkReport.portfolioReturn]과
     * 출발점이 달랐다.
     *
     * - 앵커: 기간 시작 이전(포함) 마지막 관측 — [ReturnsCalculator.periodTwrPercent]와 같은 규칙이라 마지막 점이
     *   헤드라인과 같다.
     * - 그날까지의 구간 수익률([ReturnsCalculator.segmentReturns])을 체인링킹. 분모 ≤ 0으로 빠진 구간은 직전 값을 잇는다.
     * - 앵커가 없으면(스냅샷이 기간 시작을 못 덮음) 모든 점이 null — 헤드라인이 "데이터 부족"인 것과 같다.
     */
    private fun portfolioTwrLine(
        fullSeries: List<DailyPerf>,
        flows: List<com.allfolio.report.domain.returns.Flow>,
        since: LocalDate,
    ): (LocalDate) -> BigDecimal? {
        val sorted = fullSeries.sortedBy { it.date }
        val anchor = sorted.lastOrNull { !it.date.isAfter(since) }?.date ?: return { null }
        val navPoints = sorted.map { com.allfolio.report.domain.returns.NavPoint(it.date, it.nav) }
        val cumulative = java.util.TreeMap<LocalDate, BigDecimal>()
        cumulative[anchor] = BigDecimal.ZERO
        var growth = BigDecimal.ONE
        for (seg in com.allfolio.report.domain.returns.ReturnsCalculator.segmentReturns(navPoints, flows)) {
            if (!seg.date.isAfter(anchor)) continue
            growth = growth.multiply(BigDecimal.ONE + seg.ratio, java.math.MathContext(20, RoundingMode.HALF_UP))
            cumulative[seg.date] = growth - BigDecimal.ONE
        }
        return { date ->
            cumulative.floorEntry(date)?.value
                ?.multiply(BigDecimal(100))
                ?.setScale(2, RoundingMode.HALF_UP)
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

        /** 성과 화면 기간 카드 — 순서가 응답 맵 순서다 */
        private val CARD_PERIODS = listOf("1W", "1M", "3M", "YTD", "1Y")

        /** 일간 수익률 percent 소수 자릿수 — 하루치는 작아서 2자리면 0.00%로 뭉개진다 */
        private const val DAILY_PCT_SCALE = 4
    }
}
