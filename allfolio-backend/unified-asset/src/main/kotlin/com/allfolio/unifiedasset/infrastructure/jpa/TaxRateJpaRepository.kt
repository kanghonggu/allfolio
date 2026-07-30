package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.infrastructure.entity.TaxRateEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface TaxRateJpaRepository : JpaRepository<TaxRateEntity, UUID> {
    fun findAllByOrderByCountryAscIncomeTypeAscEffectiveStartDesc(): List<TaxRateEntity>

    fun findByCountryAndIncomeTypeAndEffectiveEndIsNull(country: String, incomeType: IncomeType): TaxRateEntity?

    @Query(
        "SELECT t FROM TaxRateEntity t WHERE t.country = :country AND t.incomeType = :incomeType " +
            "AND t.effectiveStart <= :date AND (t.effectiveEnd IS NULL OR t.effectiveEnd >= :date)",
    )
    fun findEffective(
        @Param("country") country: String,
        @Param("incomeType") incomeType: IncomeType,
        @Param("date") date: LocalDate,
    ): TaxRateEntity?
}
