package com.allfolio.portfolio.infrastructure.entity

import com.allfolio.portfolio.domain.Portfolio
import com.allfolio.portfolio.domain.PortfolioId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "portfolios")
class PortfolioEntity(
    @Id
    @Column(columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(nullable = false)
    val name: String,

    @Column(name = "base_currency", nullable = false, length = 10)
    val baseCurrency: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,
) {
    fun toDomain(): Portfolio = Portfolio.reconstruct(
        id = PortfolioId.of(id),
        tenantId = userId,
        name = name,
        baseCurrency = baseCurrency,
        reportingCurrency = baseCurrency,
        benchmarkId = null,
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(portfolio: Portfolio): PortfolioEntity = PortfolioEntity(
            id = portfolio.id.value,
            userId = portfolio.tenantId,
            name = portfolio.name,
            baseCurrency = portfolio.baseCurrency,
            createdAt = portfolio.createdAt,
        )
    }
}
