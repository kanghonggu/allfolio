package com.allfolio.auth

import com.allfolio.portfolio.application.port.PortfolioRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PortfolioAuthorizationService(
    private val portfolioRepository: PortfolioRepository,
) {
    fun requireOwnedPortfolio(userId: UUID, portfolioId: UUID) {
        if (portfolioRepository.findByIdAndUserId(portfolioId, userId) == null) {
            throw NoSuchElementException("Portfolio not found: $portfolioId")
        }
    }
}
