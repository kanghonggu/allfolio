package com.allfolio.portfolio.domain

import java.time.LocalDateTime
import java.util.UUID

class Portfolio private constructor(
    val id: PortfolioId,
    val tenantId: UUID,
    val baseCurrency: String,
    name: String,
    reportingCurrency: String,
    benchmarkId: UUID?,
    val createdAt: LocalDateTime,
) {
    var name: String = name
        private set

    var reportingCurrency: String = reportingCurrency
        private set

    var benchmarkId: UUID? = benchmarkId
        private set

    fun rename(newName: String) {
        if (newName.isBlank()) throw PortfolioException.blankName()
        name = newName
    }

    fun assignBenchmark(newBenchmarkId: UUID) {
        benchmarkId = newBenchmarkId
    }

    fun removeBenchmark() {
        benchmarkId = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Portfolio) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Portfolio(id=$id, name=$name, baseCurrency=$baseCurrency)"

    companion object {
        fun create(
            tenantId: UUID,
            name: String,
            baseCurrency: String,
            reportingCurrency: String,
            benchmarkId: UUID? = null,
        ): Portfolio {
            if (name.isBlank()) throw PortfolioException.blankName()
            if (baseCurrency.isBlank()) throw PortfolioException.blankBaseCurrency()
            if (reportingCurrency.isBlank()) throw PortfolioException.blankReportingCurrency()

            return Portfolio(
                id = PortfolioId.newId(),
                tenantId = tenantId,
                name = name,
                baseCurrency = baseCurrency.uppercase(),
                reportingCurrency = reportingCurrency.uppercase(),
                benchmarkId = benchmarkId,
                createdAt = LocalDateTime.now(),
            )
        }

        fun reconstruct(
            id: PortfolioId,
            tenantId: UUID,
            name: String,
            baseCurrency: String,
            reportingCurrency: String,
            benchmarkId: UUID?,
            createdAt: LocalDateTime,
        ): Portfolio = Portfolio(
            id = id,
            tenantId = tenantId,
            name = name,
            baseCurrency = baseCurrency,
            reportingCurrency = reportingCurrency,
            benchmarkId = benchmarkId,
            createdAt = createdAt,
        )
    }
}
