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
    val periodReturn: BigDecimal?,   // 기간 첫 종가 대비 마지막 종가 수익률 — ratio(0~1)
    val excessReturn: BigDecimal?,   // twr − periodReturn — ratio
    val series: List<NavPoint>,      // 포트폴리오 기초 NAV로 정규화 — NAV 곡선에 같은 축으로 겹침
)

/**
 * 기간 수익의 자산/환율 분해 (AF-106).
 *
 * 도메인은 ratio(0~1 스케일) 유지 — percent 변환은 [com.allfolio.unifiedasset.api.ReportController] 한 곳뿐.
 *
 * @param currencies 기간 중 보유한 비-KRW 통화
 */
data class CurrencyAttribution(
    val assetContribution: BigDecimal,
    val fxContribution: BigDecimal,
    val currencies: List<String>,
)

/**
 * 도메인 결과는 ratio(0~1) 단위 유지 — 월간 리포트 아카이브 등 내부 소비자와의 호환.
 * /api/reports/returns 응답은 ReportController에서 percent(0~100)로 변환된다 (QA 후속 #1).
 */
data class ReturnsAnalysis(
    val from: LocalDate,
    val to: LocalDate,
    val asOfDate: LocalDate,
    val summary: PeriodReturns,
    val navSeries: List<NavPoint>,
    val benchmark: BenchmarkComparison?,
    val currencyAttribution: CurrencyAttribution?,
)

/** SCR-RPT-04 인터랙티브 분석: 임의 기간 TWR/MWR + BM 비교 — 아카이브 없이 on-the-fly */
@Service
class GetReturnsAnalysisUseCase(
    private val navSource: NavHistorySource,
    private val cashFlowRepository: CashFlowRepository,
    private val userBenchmark: UserBenchmarkLookup,
    private val benchmarkStore: BenchmarkDailyStore,
    private val navFxSource: NavFxHistorySource,
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
            currencyAttribution = attribution(userId, from, to, flows),
        )
    }

    /**
     * 노출 조건: 관측 2일 이상 **그리고** 비-KRW 통화가 하나 이상.
     *
     * **둘을 순차 가드로 각각 거른다 — 논리곱이다.** 어느 하나라도 통과 못 하면 null이다.
     * 이걸 논리합으로 느슨하게 하면 원화만 가진 사용자에게 자산 100%·환율 0%짜리 의미 없는
     * 블록이 뜬다. 화면은 null이면 블록 자체를 안 그린다 —
     * "수집 중입니다" 안내를 넣지 않는 이유는 외화 자산이 없는 사용자에게 그게 영원히
     * 오지 않을 것을 기다리게 하기 때문이다.
     *
     * 임계값이 '며칠'이 아니라 '관측 2건'인 것은 화면·대시보드가 이미 쓰는 규약이다.
     */
    private fun attribution(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
        flows: List<Flow>,
    ): CurrencyAttribution? {
        val series = navFxSource.navFxSeries(userId, from, to)
        if (series.size < 2) return null

        val currencies = navFxSource.currenciesIn(userId, from, to).filter { it != "KRW" }.sorted()
        if (currencies.isEmpty()) return null

        val result = ReturnsCalculator.attribute(series, flows, from, to) ?: return null
        return CurrencyAttribution(result.assetContribution, result.fxContribution, currencies)
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
