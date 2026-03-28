package com.allfolio.portfolio.domain

import com.allfolio.common.domain.DomainException

class PortfolioException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : DomainException(errorCode, message, cause) {

    companion object {
        fun notFound(id: PortfolioId) =
            PortfolioException("PORTFOLIO_NOT_FOUND", "Portfolio not found: $id")

        fun blankName() =
            PortfolioException("PORTFOLIO_BLANK_NAME", "Portfolio name must not be blank")

        fun blankBaseCurrency() =
            PortfolioException("PORTFOLIO_BLANK_BASE_CURRENCY", "Base currency must not be blank")

        fun blankReportingCurrency() =
            PortfolioException("PORTFOLIO_BLANK_REPORTING_CURRENCY", "Reporting currency must not be blank")
    }
}
