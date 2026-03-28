package com.allfolio.api.snapshot

import com.allfolio.snapshot.application.GenerateSnapshotCommand
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class GenerateSnapshotRequest(
    @field:NotNull
    val tenantId: UUID,

    @field:NotNull
    val portfolioId: UUID,

    @field:NotNull
    val date: LocalDate,

    @field:NotNull
    val marketPrices: Map<UUID, BigDecimal>,

    val yesterdayNav: BigDecimal = BigDecimal.ZERO,
    val externalCashFlow: BigDecimal = BigDecimal.ZERO,
    val previousCumulativeReturn: BigDecimal? = null,
    val benchmarkReturn: BigDecimal? = null,
    val recentDailyReturns: List<BigDecimal> = emptyList(),
) {
    fun toCommand(): GenerateSnapshotCommand = GenerateSnapshotCommand(
        tenantId                 = tenantId,
        portfolioId              = portfolioId,
        date                     = date,
        marketPrices             = marketPrices,
        yesterdayNav             = yesterdayNav,
        externalCashFlow         = externalCashFlow,
        previousCumulativeReturn = previousCumulativeReturn,
        benchmarkReturn          = benchmarkReturn,
        recentDailyReturns       = recentDailyReturns,
    )
}
