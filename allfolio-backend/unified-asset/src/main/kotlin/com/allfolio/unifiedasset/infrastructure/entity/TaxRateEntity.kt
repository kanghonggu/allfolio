package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "tax_rates")
class TaxRateEntity(
    @Id val id: UUID,
    @Column(name = "country", nullable = false, length = 2) val country: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "income_type", nullable = false, length = 20) val incomeType: IncomeType,
    @Column(name = "rate", nullable = false, precision = 6, scale = 3) val rate: BigDecimal,
    @Column(name = "effective_start", nullable = false) val effectiveStart: LocalDate,
    @Column(name = "effective_end") val effectiveEnd: LocalDate?,
    @Column(name = "updated_by") val updatedBy: UUID?,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
    @Column(name = "updated_at", nullable = false) val updatedAt: LocalDateTime,
) {
    fun toDomain() = TaxRate(id, country, incomeType, rate, effectiveStart, effectiveEnd, updatedBy, createdAt, updatedAt)

    companion object {
        fun from(d: TaxRate) = TaxRateEntity(
            d.id, d.country, d.incomeType, d.rate, d.effectiveStart, d.effectiveEnd, d.updatedBy, d.createdAt, d.updatedAt,
        )
    }
}
