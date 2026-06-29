package com.allfolio.portfolio.application.port

import com.allfolio.portfolio.domain.Portfolio
import java.util.UUID

interface PortfolioRepository {
    fun save(portfolio: Portfolio): Portfolio
    fun findByIdAndUserId(id: UUID, userId: UUID): Portfolio?
    fun findByUserId(userId: UUID): List<Portfolio>
    fun softDelete(id: UUID, userId: UUID): Int
}
