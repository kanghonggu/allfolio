package com.allfolio.unifiedasset.api

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.PeriodReturns
import com.allfolio.unifiedasset.application.usecase.BenchmarkComparison
import com.allfolio.unifiedasset.application.usecase.DividendReportService
import com.allfolio.unifiedasset.application.usecase.EsgReportService
import com.allfolio.unifiedasset.application.usecase.GetReturnsAnalysisUseCase
import com.allfolio.unifiedasset.application.usecase.ReportService
import com.allfolio.unifiedasset.application.usecase.ReturnsAnalysis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * QA 후속 #1 — /api/reports/returns 응답 수익률은 percent(0~100) 단위.
 * 같은 값을 dashboard는 2060.43(percent), 이 API는 20.60(ratio)으로 내려보내던 불일치 방지.
 */
class ReportControllerReturnsPercentTest {

    private val userId = UUID.randomUUID()
    private val from = LocalDate.of(2026, 6, 1)
    private val to = LocalDate.of(2026, 6, 30)

    @Test
    fun `returns 응답은 twr-mwr-benchmark 수익률을 percent로 변환한다`() {
        val analysisUseCase = mock(GetReturnsAnalysisUseCase::class.java)
        val ratioAnalysis = ReturnsAnalysis(
            from = from, to = to, asOfDate = to,
            summary = PeriodReturns(
                twr = BigDecimal("0.1"), mwr = BigDecimal("0.098"),
                startNav = BigDecimal("1000"), endNav = BigDecimal("1100"),
                netFlow = BigDecimal.ZERO, investmentPnl = BigDecimal("100"),
            ),
            navSeries = listOf(NavPoint(from, BigDecimal("1000")), NavPoint(to, BigDecimal("1100"))),
            benchmark = BenchmarkComparison(
                indexType = "KOSPI", label = "코스피",
                periodReturn = BigDecimal("0.05"), excessReturn = BigDecimal("0.05"),
                series = emptyList(),
            ),
        )
        `when`(analysisUseCase.analyze(any() ?: userId, any() ?: from, any() ?: to)).thenReturn(ratioAnalysis)

        val controller = ReportController(
            mock(ReportService::class.java),
            mock(DividendReportService::class.java),
            mock(EsgReportService::class.java),
            analysisUseCase,
        )

        val response = controller.returns(userId, from, to)

        assertEquals(0, BigDecimal("10.00").compareTo(response.summary.twr))
        assertEquals(0, BigDecimal("9.80").compareTo(response.summary.mwr))
        assertEquals(0, BigDecimal("5.00").compareTo(response.benchmark!!.periodReturn))
        assertEquals(0, BigDecimal("5.00").compareTo(response.benchmark!!.excessReturn))
        // NAV·금액 필드는 그대로
        assertEquals(0, BigDecimal("1000").compareTo(response.summary.startNav))
        assertEquals(0, BigDecimal("100").compareTo(response.summary.investmentPnl))
    }
}
