package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.PeriodReturns
import com.allfolio.report.domain.returns.ReturnsCalculator
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

data class ReturnsAnalysis(
    val from: LocalDate,
    val to: LocalDate,
    val asOfDate: LocalDate,
    val summary: PeriodReturns,
    val navSeries: List<NavPoint>,
)

/** SCR-RPT-04 인터랙티브 분석: 임의 기간 TWR/MWR — 아카이브 없이 on-the-fly */
@Service
class GetReturnsAnalysisUseCase(
    private val navSource: NavHistorySource,
    private val cashFlowRepository: CashFlowRepository,
) {
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
        return ReturnsAnalysis(
            from = from,
            to = to,
            asOfDate = sorted.last().date,
            summary = ReturnsCalculator.calculate(sorted, flows, from, to),
            navSeries = sorted,
        )
    }
}
