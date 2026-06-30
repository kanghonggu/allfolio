package com.allfolio.auth

import com.allfolio.portfolio.application.port.PortfolioRepository
import com.allfolio.portfolio.domain.Portfolio
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class PortfolioAuthorizationServiceTest {

    @Test
    fun `owned portfolio passes authorization`() {
        val userId = UUID.randomUUID()
        val portfolio = Portfolio.create(
            tenantId = userId,
            name = "Core",
            baseCurrency = "KRW",
            reportingCurrency = "KRW",
        )
        val service = PortfolioAuthorizationService(FakePortfolioRepository(listOf(portfolio)))

        assertDoesNotThrow {
            service.requireOwnedPortfolio(userId, portfolio.id.value)
        }
    }

    @Test
    fun `another user's portfolio is hidden as not found`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val portfolio = Portfolio.create(
            tenantId = ownerId,
            name = "Core",
            baseCurrency = "KRW",
            reportingCurrency = "KRW",
        )
        val service = PortfolioAuthorizationService(FakePortfolioRepository(listOf(portfolio)))

        assertThrows(NoSuchElementException::class.java) {
            service.requireOwnedPortfolio(otherUserId, portfolio.id.value)
        }
    }

    @Test
    fun `missing portfolio is not found`() {
        val service = PortfolioAuthorizationService(FakePortfolioRepository(emptyList()))

        assertThrows(NoSuchElementException::class.java) {
            service.requireOwnedPortfolio(UUID.randomUUID(), UUID.randomUUID())
        }
    }

    private class FakePortfolioRepository(
        portfolios: List<Portfolio>,
    ) : PortfolioRepository {
        private val portfoliosById = portfolios.associateBy { it.id.value }

        override fun save(portfolio: Portfolio): Portfolio = portfolio

        override fun findByIdAndUserId(id: UUID, userId: UUID): Portfolio? =
            portfoliosById[id]?.takeIf { it.tenantId == userId }

        override fun findByUserId(userId: UUID): List<Portfolio> =
            portfoliosById.values.filter { it.tenantId == userId }

        override fun softDelete(id: UUID, userId: UUID): Int = 0
    }
}
