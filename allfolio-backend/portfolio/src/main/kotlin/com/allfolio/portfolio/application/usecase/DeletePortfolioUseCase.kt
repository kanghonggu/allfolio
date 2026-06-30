package com.allfolio.portfolio.application.usecase

import com.allfolio.portfolio.application.port.PortfolioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DeletePortfolioUseCase(
    private val repository: PortfolioRepository,
) {
    @Transactional
    fun execute(userId: UUID, id: UUID) {
        repository.findByIdAndUserId(id, userId)
            ?: throw NoSuchElementException("Portfolio not found: $id")

        val deleted = repository.softDelete(id, userId)
        if (deleted == 0) {
            throw NoSuchElementException("Portfolio not found: $id")
        }
    }
}
