package com.allfolio.dashboard

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.usecase.currentValueInKrw
import com.allfolio.unifiedasset.application.usecase.loanAmountInKrw
import com.allfolio.unifiedasset.application.usecase.navInKrw
import com.allfolio.unifiedasset.domain.asset.AssetLiquidityType
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.RiskDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.BenchmarkDailyJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional(readOnly = true)
class GetDashboardUseCase(
    private val assetRepository: AssetRepository,
    private val performanceRepo: PerformanceDailyJpaRepository,
    private val riskRepo: RiskDailyJpaRepository,
    private val benchmarkRepo: BenchmarkDailyJpaRepository,
    private val fx: FxConverter,
) {
    fun execute(userId: UUID): DashboardResponse {
        val assets = assetRepository.findByUserId(userId)
        val liquidAssets   = assets.filter { it.liquidityType == AssetLiquidityType.LIQUID }
        val illiquidAssets = assets.filter { it.liquidityType == AssetLiquidityType.ILLIQUID }

        // QA P1 #9/#11: 통화 혼재 자산은 navInKrw 규약대로 KRW 환산 후 합산,
        // KRW 집계는 소수점 없이(scale 0) — performance_daily.nav(KRW)와 비교 가능해진다.
        val liquidValue   = liquidAssets.navInKrw(fx).setScale(0, RoundingMode.HALF_UP)
        val illiquidValue = illiquidAssets.navInKrw(fx).setScale(0, RoundingMode.HALF_UP)
        val debtValue     = assets.fold(BigDecimal.ZERO) { acc, a -> acc + a.loanAmountInKrw(fx) }
            .setScale(0, RoundingMode.HALF_UP)
        val totalNow      = liquidValue.add(illiquidValue).subtract(debtValue)

        // 30일 전 NAV
        val today   = LocalDate.now()
        val date30d = today.minusDays(30)
        val perf30d = performanceRepo
            .findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(userId, date30d.plusDays(1))
        val nav30d        = perf30d?.nav ?: BigDecimal.ZERO
        val change30d     = if (nav30d > BigDecimal.ZERO)
            totalNow.subtract(nav30d).setScale(0, RoundingMode.HALF_UP)
        else BigDecimal.ZERO
        val changeRate30d = if (nav30d > BigDecimal.ZERO)
            change30d.divide(nav30d, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100))
        else BigDecimal.ZERO

        // 수익률 히스토리 (YTD)
        val ytdStart    = LocalDate.of(today.year, 1, 1)
        val perfHistory = performanceRepo.findByIdPortfolioIdAndIdDateBetween(userId, ytdStart, today)
        val dataDays    = perfHistory.size
        val latestPerf  = perfHistory.maxByOrNull { it.id.date }
        val ytdStartPerf = perfHistory.minByOrNull { it.id.date }

        val returnYtd = if (ytdStartPerf != null && ytdStartPerf.nav > BigDecimal.ZERO && latestPerf != null)
            latestPerf.nav.subtract(ytdStartPerf.nav)
                .divide(ytdStartPerf.nav, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        else null

        val perf1mRef = performanceRepo
            .findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(userId, today.minusDays(29))
        val return1m = if (perf1mRef != null && perf1mRef.nav > BigDecimal.ZERO && latestPerf != null)
            latestPerf.nav.subtract(perf1mRef.nav)
                .divide(perf1mRef.nav, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        else null

        val perf3mRef = performanceRepo
            .findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(userId, today.minusDays(89))
        val return3m = if (perf3mRef != null && perf3mRef.nav > BigDecimal.ZERO && latestPerf != null)
            latestPerf.nav.subtract(perf3mRef.nav)
                .divide(perf3mRef.nav, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        else null

        // MDD
        val latestRisk = riskRepo.findTopByIdPortfolioIdOrderByIdDateDesc(userId)
        val mdd = latestRisk?.maxDrawdown?.multiply(BigDecimal(100))

        // Phase 3: Sharpe, VaR, 변동성
        val riskFreeRate = BigDecimal("3.5")
        val annualVol    = latestRisk?.annualizedVolatility?.multiply(BigDecimal(100))
        val sharpe = if (returnYtd != null && annualVol != null && annualVol > BigDecimal.ZERO && dataDays >= 10)
            returnYtd.subtract(riskFreeRate).divide(annualVol, 4, RoundingMode.HALF_UP)
        else null
        val var95Amount = latestRisk?.var95?.multiply(liquidValue)

        // 벤치마크 KOSPI YTD
        val kospiNow   = benchmarkRepo.findTopByIdIndexTypeOrderByIdDateDesc("KOSPI")?.closeValue
        val kospiStart = benchmarkRepo
            .findTopByIdIndexTypeAndIdDateLessThanEqualOrderByIdDateDesc("KOSPI", ytdStart.plusDays(5))
            ?.closeValue
        val kospiYtd = if (kospiNow != null && kospiStart != null && kospiStart > BigDecimal.ZERO)
            kospiNow.subtract(kospiStart).divide(kospiStart, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        else null

        fun buildReturn(value: BigDecimal?, vsKospi: BigDecimal? = null): MetricValueDto? {
            value ?: return null
            return MetricValueDto(
                value            = value.setScale(2, RoundingMode.HALF_UP),
                grade            = MetricsCalculator.returnToGrade(value).name,
                stars            = MetricsCalculator.returnToStars(value),
                benchmarkVsKospi = vsKospi?.setScale(2, RoundingMode.HALF_UP),
                benchmarkVsBtc   = null,
                dataWarning      = MetricsCalculator.dataWarning(dataDays),
            )
        }

        val metrics = MetricsDto(
            returnYtd = buildReturn(
                returnYtd,
                if (returnYtd != null && kospiYtd != null) MetricsCalculator.pctDiff(returnYtd, kospiYtd) else null,
            ),
            return1m   = buildReturn(return1m),
            return3m   = buildReturn(return3m),
            mdd = mdd?.let {
                MetricValueDto(
                    value            = it.setScale(2, RoundingMode.HALF_UP),
                    grade            = MetricsCalculator.mddToGrade(it).name,
                    stars            = MetricsCalculator.mddToStars(it),
                    benchmarkVsKospi = null,
                    benchmarkVsBtc   = null,
                    dataWarning      = MetricsCalculator.dataWarning(dataDays),
                )
            },
            sharpe = sharpe?.let {
                MetricValueDto(
                    value            = it.setScale(2, RoundingMode.HALF_UP),
                    grade            = MetricsCalculator.sharpeToGrade(it).name,
                    stars            = MetricsCalculator.sharpeToStars(it),
                    benchmarkVsKospi = null,
                    benchmarkVsBtc   = null,
                    dataWarning      = MetricsCalculator.dataWarning(dataDays),
                )
            },
            var95 = var95Amount?.let {
                MetricValueDto(
                    value            = it.setScale(0, RoundingMode.HALF_UP),
                    grade            = MetricGrade.WARN.name,
                    stars            = 2,
                    benchmarkVsKospi = null,
                    benchmarkVsBtc   = null,
                    dataWarning      = MetricsCalculator.dataWarning(dataDays),
                )
            },
            volatility = annualVol?.let {
                MetricValueDto(
                    value            = it.setScale(2, RoundingMode.HALF_UP),
                    grade            = MetricsCalculator.volatilityToGrade(it).name,
                    stars            = MetricsCalculator.volatilityToStars(it),
                    benchmarkVsKospi = null,
                    benchmarkVsBtc   = null,
                    dataWarning      = MetricsCalculator.dataWarning(dataDays),
                )
            },
        )

        // 자산 배분 (LIQUID만, ratio는 decimal fraction [0,1])
        val totalLiquid = liquidValue.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
        val allocation  = liquidAssets
            .groupBy { it.type.name }
            .map { (type, list) ->
                val typeValue = list.navInKrw(fx).setScale(0, RoundingMode.HALF_UP)
                val ratio     = MetricsCalculator.weightOf(typeValue, totalLiquid)
                AllocationDto(
                    type  = type,
                    ratio = ratio,
                    value = typeValue,
                    grade = MetricsCalculator.concentrationToGrade(ratio).name,
                )
            }
            .sortedByDescending { it.value }

        // 포지션 (LIQUID만) — 표시는 원통화 유지, 정렬·비중은 KRW 환산 기준
        val positions = liquidAssets
            .sortedByDescending { it.currentValueInKrw(fx) }
            .map { a ->
                PositionDto(
                    id           = a.id,
                    name         = a.name,
                    symbol       = a.symbol,
                    type         = a.type.name,
                    currentValue = a.currentValue,
                    returnRate   = a.returnRate().setScale(2, RoundingMode.HALF_UP),
                    weight       = MetricsCalculator.weightOf(a.currentValueInKrw(fx), totalLiquid)
                        .setScale(4, RoundingMode.HALF_UP),
                    currency     = a.currency,
                )
            }

        // 실물자산 (ILLIQUID만) — 정렬은 KRW 환산 기준
        val realAssets = illiquidAssets
            .sortedByDescending { it.currentValueInKrw(fx) }
            .map { a ->
                RealAssetDto(
                    id                = a.id,
                    name              = a.name,
                    type              = a.type.name,
                    value             = a.currentValue,
                    currency          = a.currency,
                    maturityDate      = a.maturityDate,
                    daysUntilMaturity = a.maturityDate?.let {
                        ChronoUnit.DAYS.between(today, it).takeIf { d -> d >= 0 }
                    },
                )
            }

        return DashboardResponse(
            netWorth = NetWorthDto(
                total         = totalNow,
                liquid        = liquidValue,
                illiquid      = illiquidValue,
                debt          = debtValue,
                change30d     = change30d,
                changeRate30d = changeRate30d.setScale(2, RoundingMode.HALF_UP),
            ),
            portfolio = PortfolioDto(
                totalValue = liquidValue,
                currency   = "KRW",
                metrics    = metrics,
                allocation = allocation,
                positions  = positions,
            ),
            realAssets = realAssets,
        )
    }
}
