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
        /** 매매 대금을 뺀 구간 수익률. 첫 관측일처럼 구간이 없는 날은 null */
        val dailyReturn: BigDecimal?,
        /** 첫 관측일부터 매매 대금을 뺀 구간 수익률을 체인링킹한 값(TWR). 첫 관측일은 0 */
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
            dailyReturn: BigDecimal?,
            cumulativeReturn: BigDecimal,
            risk: DailyRisk?,
        ): PortfolioSnapshotResponse = PortfolioSnapshotResponse(
            portfolioId = performance.id.portfolioId,
            date        = performance.id.date,
            performance = PerformanceSummary(
                nav              = performance.nav,
                dailyReturn      = dailyReturn,
                cumulativeReturn = cumulativeReturn,
                benchmarkReturn  = performance.benchmarkReturn,
                // 저장 alpha는 저장 daily_return(미조정)에서 뺀 값이라 쓰지 않는다
                alpha            = performance.benchmarkReturn?.let { b -> dailyReturn?.subtract(b) },
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
