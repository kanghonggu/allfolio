package com.allfolio.api.portfolio

import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.unifiedasset.application.usecase.DailyRisk
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class PortfolioSnapshotResponse(
    val portfolioId: UUID,
    val date: LocalDate,
    val performance: PerformanceSummary,
    /** 구간 수익률이 2건 미만이면 null — 0으로 채우면 "변동성 0%"로 읽힌다 */
    val risk: RiskSummary?,
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
            risk: DailyRisk?,
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
            risk = risk?.let {
                RiskSummary(
                    volatility           = it.volatility,
                    annualizedVolatility = it.annualizedVolatility,
                    var95                = it.var95,
                    maxDrawdown          = it.maxDrawdown,
                )
            },
        )
    }
}
