package com.allfolio.unifiedasset.api

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.PeriodReturns
import com.allfolio.unifiedasset.application.usecase.BenchmarkComparison
import com.allfolio.unifiedasset.application.usecase.CurrencyAttribution
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
            currencyAttribution = null,
        )
        `when`(analysisUseCase.analyze(any() ?: userId, any() ?: from, any() ?: to)).thenReturn(ratioAnalysis)

        val controller = controllerWith(analysisUseCase)

        val response = controller.returns(userId, from, to)

        assertEquals(0, BigDecimal("10.00").compareTo(response.summary.twr))
        assertEquals(0, BigDecimal("9.80").compareTo(response.summary.mwr))
        assertEquals(0, BigDecimal("5.00").compareTo(response.benchmark!!.periodReturn))
        assertEquals(0, BigDecimal("5.00").compareTo(response.benchmark!!.excessReturn))
        // NAV·금액 필드는 그대로
        assertEquals(0, BigDecimal("1000").compareTo(response.summary.startNav))
        assertEquals(0, BigDecimal("100").compareTo(response.summary.investmentPnl))
    }

    /**
     * AF-106 — 분해도 같은 경계에서 percent로 바뀐다.
     * 도메인 ratio 0.10이 응답에서 0.10으로 남으면 화면엔 "0.1%"로 찍힌다 (AF-104와 같은 스케일 사고).
     */
    @Test
    fun `returns 응답은 자산-환율 기여도를 percent로 변환한다`() {
        val analysisUseCase = mock(GetReturnsAnalysisUseCase::class.java)
        val ratioAnalysis = ReturnsAnalysis(
            from = from, to = to, asOfDate = to,
            summary = PeriodReturns(
                twr = BigDecimal("0.155"), mwr = null,
                startNav = BigDecimal("1000"), endNav = BigDecimal("1155"),
                netFlow = BigDecimal.ZERO, investmentPnl = BigDecimal("155"),
            ),
            navSeries = emptyList(),
            benchmark = null,
            currencyAttribution = CurrencyAttribution(
                assetContribution = BigDecimal("0.10"),
                fxContribution = BigDecimal("-0.05"),
                currencies = listOf("USD"),
            ),
        )
        `when`(analysisUseCase.analyze(any() ?: userId, any() ?: from, any() ?: to)).thenReturn(ratioAnalysis)

        val response = controllerWith(analysisUseCase).returns(userId, from, to)

        val attribution = response.currencyAttribution!!
        assertEquals(0, BigDecimal("10.00").compareTo(attribution.assetContribution))
        assertEquals(0, BigDecimal("-5.00").compareTo(attribution.fxContribution))
        // 통화 목록은 변환 대상이 아니다
        assertEquals(listOf("USD"), attribution.currencies)
    }

    private fun controllerWith(analysisUseCase: GetReturnsAnalysisUseCase) = ReportController(
        mock(ReportService::class.java),
        mock(DividendReportService::class.java),
        mock(EsgReportService::class.java),
        analysisUseCase,
    )
}
