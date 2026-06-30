package com.allfolio.test

import com.allfolio.portfolio.application.port.PortfolioRepository
import com.allfolio.portfolio.domain.Portfolio
import java.util.UUID

class FakePortfolioRepository(
    portfolios: List<Portfolio> = emptyList(),
) : PortfolioRepository {
    private val portfoliosById = portfolios.associateBy { it.id.value }

    override fun save(portfolio: Portfolio): Portfolio = portfolio

    override fun findByIdAndUserId(id: UUID, userId: UUID): Portfolio? =
        portfoliosById[id]?.takeIf { it.tenantId == userId }

    override fun findByUserId(userId: UUID): List<Portfolio> =
        portfoliosById.values.filter { it.tenantId == userId }

    override fun softDelete(id: UUID, userId: UUID): Int = 0
}
