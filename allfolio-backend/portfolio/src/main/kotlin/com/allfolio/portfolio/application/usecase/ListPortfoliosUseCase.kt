package com.allfolio.portfolio.application.usecase

import com.allfolio.portfolio.application.port.PortfolioRepository
import com.allfolio.portfolio.domain.Portfolio
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ListPortfoliosUseCase(
    private val repository: PortfolioRepository,
) {
    @Transactional(readOnly = true)
    fun execute(userId: UUID): List<Portfolio> = repository.findByUserId(userId)
}
