package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/reports")
class ReportController(
    private val svc: ReportService,
    private val dividendSvc: DividendReportService,
    private val esgSvc: EsgReportService,
    private val returnsAnalysis: GetReturnsAnalysisUseCase,
) {

    /** SCR-RPT-04 인터랙티브 수익률 분석 — 임의 기간 TWR/MWR (아카이브 안 함) */
    @GetMapping("/returns")
    fun returns(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ReturnsAnalysis {
        val analysis = returnsAnalysis.analyze(userId, from, to)
        // 응답 수익률은 percent(0~100)로 통일 (QA 후속 #1) — 같은 값을 dashboard는 2060.43(percent),
        // 이 API는 20.60(ratio)으로 내려보내던 단위 불일치 제거. 도메인은 ratio 유지(월간 아카이브 호환),
        // 변환은 API 경계인 여기 한 곳뿐.
        fun java.math.BigDecimal.pct() =
            multiply(java.math.BigDecimal(100)).setScale(2, java.math.RoundingMode.HALF_UP)
        return analysis.copy(
            summary = analysis.summary.copy(
                twr = analysis.summary.twr?.pct(),
                mwr = analysis.summary.mwr?.pct(),
            ),
            benchmark = analysis.benchmark?.copy(
                periodReturn = analysis.benchmark.periodReturn?.pct(),
                excessReturn = analysis.benchmark.excessReturn?.pct(),
            ),
        )
    }

    @ExceptionHandler(InsufficientDataException::class)
    fun insufficientData(e: InsufficientDataException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "insufficient data")))

    @GetMapping("/summary")
    fun summary(@RequestHeader("X-User-Id") userId: UUID): SummaryReport =
        svc.summary(userId)

    @GetMapping("/allocation")
    fun allocation(@RequestHeader("X-User-Id") userId: UUID): AllocationReport =
        svc.allocation(userId)

    @GetMapping("/performance")
    fun performance(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(defaultValue = "1M") period: String,
    ): PerformanceReport = svc.performance(userId, period)

    @GetMapping("/risk")
    fun risk(@RequestHeader("X-User-Id") userId: UUID): RiskReport =
        svc.risk(userId)

    @GetMapping("/positions")
    fun positions(@RequestHeader("X-User-Id") userId: UUID): PositionsReport =
        svc.positions(userId)

    @GetMapping("/benchmark")
    fun benchmark(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(defaultValue = "YTD") period: String,
    ): BenchmarkReport = svc.benchmark(userId, period)

    @GetMapping("/networth")
    fun networth(@RequestHeader("X-User-Id") userId: UUID): NetWorthReport =
        svc.networth(userId)

    @GetMapping("/monthly-pnl")
    fun monthlyPnl(@RequestHeader("X-User-Id") userId: UUID): MonthlyPnlReport =
        svc.monthlyPnl(userId)

    @GetMapping("/dividend")
    fun dividend(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(defaultValue = "YTD") period: String,
    ): DividendReport = dividendSvc.report(userId, period)

    @GetMapping("/esg")
    fun esg(@RequestHeader("X-User-Id") userId: UUID): com.allfolio.report.domain.EsgReport =
        esgSvc.generate(userId)
}
