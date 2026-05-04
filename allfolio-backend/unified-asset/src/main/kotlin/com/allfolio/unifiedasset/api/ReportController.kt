package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.*
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/reports")
class ReportController(
    private val svc: ReportService,
    private val dividendSvc: DividendReportService,
) {

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
}
