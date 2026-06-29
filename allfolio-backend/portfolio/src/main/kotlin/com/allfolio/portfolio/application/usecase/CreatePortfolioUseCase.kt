package com.allfolio.portfolio.application.usecase

import com.allfolio.portfolio.application.port.PortfolioRepository
import com.allfolio.portfolio.domain.Portfolio
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CreatePortfolioUseCase(
    private val repository: PortfolioRepository,
) {
    @Transactional
    fun execute(userId: UUID, name: String): Portfolio =
        repository.save(
            Portfolio.create(
                tenantId = userId,
                name = name,
                baseCurrency = "KRW",
                reportingCurrency = "KRW",
                benchmarkId = null,
            )
        )
}
