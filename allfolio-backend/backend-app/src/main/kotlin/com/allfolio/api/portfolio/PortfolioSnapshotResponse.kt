package com.allfolio.api.portfolio

import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.RiskDailyEntity
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class PortfolioSnapshotResponse(
    val portfolioId: UUID,
    val date: LocalDate,
    val performance: PerformanceSummary,
    val risk: RiskSummary,
) {
    data class PerformanceSummary(
        val nav: BigDecimal,
        val dailyReturn: BigDecimal,
        val cumulativeReturn: BigDecimal,
        val benchmarkReturn: BigDecimal?,
        val alpha: BigDecimal?,
    )

    data class RiskSummary(
        val volatility: BigDecimal,
        val annualizedVolatility: BigDecimal,
        val var95: BigDecimal,
        val maxDrawdown: BigDecimal,
    )

    companion object {
        fun of(
            performance: PerformanceDailyEntity,
            risk: RiskDailyEntity,
        ): PortfolioSnapshotResponse = PortfolioSnapshotResponse(
            portfolioId = performance.id.portfolioId,
            date        = performance.id.date,
            performance = PerformanceSummary(
                nav              = performance.nav,
                dailyReturn      = performance.dailyReturn,
                cumulativeReturn = performance.cumulativeReturn,
                benchmarkReturn  = performance.benchmarkReturn,
                alpha            = performance.alpha,
            ),
            risk = RiskSummary(
                volatility           = risk.volatility,
                annualizedVolatility = risk.annualizedVolatility,
                var95                = risk.var95,
                maxDrawdown          = risk.maxDrawdown,
            ),
        )
    }
}
