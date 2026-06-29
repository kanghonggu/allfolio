package com.allfolio.portfolio.infrastructure.repository

import com.allfolio.portfolio.application.port.PortfolioRepository
import com.allfolio.portfolio.domain.Portfolio
import com.allfolio.portfolio.infrastructure.entity.PortfolioEntity
import com.allfolio.portfolio.infrastructure.jpa.PortfolioJpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class PortfolioRepositoryImpl(
    private val jpa: PortfolioJpaRepository,
) : PortfolioRepository {
    override fun save(portfolio: Portfolio): Portfolio =
        jpa.save(PortfolioEntity.fromDomain(portfolio)).toDomain()

    override fun findByIdAndUserId(id: UUID, userId: UUID): Portfolio? =
        jpa.findByIdAndUserIdAndDeletedAtIsNull(id, userId)?.toDomain()

    override fun findByUserId(userId: UUID): List<Portfolio> =
        jpa.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId).map { it.toDomain() }

    override fun softDelete(id: UUID, userId: UUID): Int =
        jpa.softDelete(id, userId, LocalDateTime.now())
}
