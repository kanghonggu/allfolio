package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.PeriodReturns
import com.allfolio.report.domain.returns.ReturnsCalculator
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.UserBenchmarkLookup
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

data class BenchmarkComparison(
    val indexType: String,
    val label: String,
    val periodReturn: BigDecimal?,   // 기간 첫 종가 대비 마지막 종가 수익률
    val excessReturn: BigDecimal?,   // twr − periodReturn
    val series: List<NavPoint>,      // 포트폴리오 기초 NAV로 정규화 — NAV 곡선에 같은 축으로 겹침
)

data class ReturnsAnalysis(
    val from: LocalDate,
    val to: LocalDate,
    val asOfDate: LocalDate,
    val summary: PeriodReturns,
    val navSeries: List<NavPoint>,
    val benchmark: BenchmarkComparison?,
)

/** SCR-RPT-04 인터랙티브 분석: 임의 기간 TWR/MWR + BM 비교 — 아카이브 없이 on-the-fly */
@Service
class GetReturnsAnalysisUseCase(
    private val navSource: NavHistorySource,
    private val cashFlowRepository: CashFlowRepository,
    private val userBenchmark: UserBenchmarkLookup,
    private val benchmarkStore: BenchmarkDailyStore,
) {
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    fun analyze(userId: UUID, from: LocalDate, to: LocalDate): ReturnsAnalysis {
        require(!from.isAfter(to)) { "조회 시작일이 종료일 이후일 수 없습니다" }
        val series = navSource.navSeries(userId, from, to)
        if (series.size < 2) {
            throw InsufficientDataException(
                "수익률 계산에 필요한 NAV 스냅샷이 부족합니다 (기간 내 ${series.size}건, 최소 2건)"
            )
        }
        val flows = cashFlowRepository.findByUserIdAndPeriod(userId, from, to)
            .map { Flow(it.flowDate, it.signedKrw()) }
        val sorted = series.sortedBy { it.date }
        val summary = ReturnsCalculator.calculate(sorted, flows, from, to)
        return ReturnsAnalysis(
            from = from,
            to = to,
            asOfDate = sorted.last().date,
            summary = summary,
            navSeries = sorted,
            benchmark = benchmarkComparison(userId, from, to, summary),
        )
    }

    private fun benchmarkComparison(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
        summary: PeriodReturns,
    ): BenchmarkComparison? {
        val type = userBenchmark.get(userId) ?: return null
        val closes = benchmarkStore.series(type, from, to)
        if (closes.size < 2) return null

        val first = closes.first().second
        if (first <= BigDecimal.ZERO) return null
        val periodReturn = closes.last().second.divide(first, mc) - BigDecimal.ONE
        val startNav = summary.startNav

        return BenchmarkComparison(
            indexType = type.name,
            label = type.label,
            periodReturn = periodReturn,
            excessReturn = summary.twr?.subtract(periodReturn),
            series = if (startNav == null) emptyList()
                     else closes.map { (date, close) -> NavPoint(date, startNav.multiply(close.divide(first, mc), mc)) },
        )
    }
}
