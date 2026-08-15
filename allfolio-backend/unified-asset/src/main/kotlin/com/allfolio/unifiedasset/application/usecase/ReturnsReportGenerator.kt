package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.PeriodReturns
import com.allfolio.report.domain.returns.ReturnsCalculator
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

class InsufficientDataException(message: String) : RuntimeException(message)

/** performance_daily NAV 시계열 조회 포트 — JDBC 구현은 인프라에 */
interface NavHistorySource {
    fun navSeries(userId: UUID, from: LocalDate, to: LocalDate): List<com.allfolio.report.domain.returns.NavPoint>
}

/** AF-106 통화별 평가액을 얹은 NAV 시계열 — 자산/환율 기여도 분해용 */
interface NavFxHistorySource {
    fun navFxSeries(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<com.allfolio.report.domain.returns.NavFxPoint>

    /** 기간 중 등장한 통화 코드 (중복 제거) */
    fun currenciesIn(userId: UUID, from: LocalDate, to: LocalDate): List<String>
}

/**
 * R-02 수익률 리포트 생성기 — #32 프레임의 첫 ReportBodyGenerator.
 * NAV(KRW)와 cash_flow(KRW 고정 환산)로 TWR/MWR·입출금 효과 분해를 계산한다.
 */
@Component
class ReturnsReportGenerator(
    private val navSource: NavHistorySource,
    private val cashFlowRepository: CashFlowRepository,
) : ReportBodyGenerator {

    override val type = ReportType.RETURNS

    private val mapper = jacksonObjectMapper()

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val periodSeries = navSource.navSeries(userId, period.start, period.end)
        if (periodSeries.size < 2) {
            throw InsufficientDataException(
                "수익률 계산에 필요한 NAV 스냅샷이 부족합니다 (기간 내 ${periodSeries.size}건, 최소 2건)"
            )
        }
        val asOfDate = periodSeries.maxOf { it.date }

        val earliest = LocalDate.of(2000, 1, 1)
        val fullSeries = navSource.navSeries(userId, earliest, period.end).sortedBy { it.date }
        val flows = cashFlowRepository.findByUserIdAndPeriod(userId, earliest, period.end)
            .map { Flow(it.flowDate, it.signedKrw()) }

        val periodResult = ReturnsCalculator.calculate(fullSeries, flows, period.start, period.end)

        val inception = fullSeries.first().date
        val standardFrom = mapOf(
            "1M" to period.end.minusMonths(1),
            "3M" to period.end.minusMonths(3),
            "6M" to period.end.minusMonths(6),
            "YTD" to period.end.withDayOfYear(1),
            "1Y" to period.end.minusYears(1),
            "SI" to inception,
        )
        val standard = standardFrom.mapValues { (_, from) ->
            ReturnsCalculator.calculate(fullSeries, flows, maxOf(from, inception), period.end).toMap()
        }

        val body = mapOf(
            "period" to periodResult.toMap(),
            "standard" to standard,
            "flowDecomposition" to mapOf(
                "startNav" to periodResult.startNav,
                "netFlow" to periodResult.netFlow,
                "investmentPnl" to periodResult.investmentPnl,
                "endNav" to periodResult.endNav,
            ),
            "navSeries" to periodSeries.sortedBy { it.date }
                .map { mapOf("date" to it.date.toString(), "nav" to it.nav) },
        )
        return GeneratedReport(asOfDate = asOfDate, bodyJson = mapper.writeValueAsString(body))
    }

    private fun PeriodReturns.toMap() = mapOf(
        "twr" to twr,
        "mwr" to mwr,
        "startNav" to startNav,
        "endNav" to endNav,
        "netFlow" to netFlow,
        "investmentPnl" to investmentPnl,
    )
}
