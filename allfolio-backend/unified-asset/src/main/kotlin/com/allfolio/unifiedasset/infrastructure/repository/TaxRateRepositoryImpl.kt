package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import com.allfolio.unifiedasset.infrastructure.entity.TaxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.TaxRateJpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class TaxRateRepositoryImpl(
    private val jpa: TaxRateJpaRepository,
) : TaxRateRepository {
    override fun findAll(): List<TaxRate> =
        jpa.findAllByOrderByCountryAscIncomeTypeAscEffectiveStartDesc().map { it.toDomain() }

    override fun findOpen(country: String, incomeType: IncomeType): TaxRate? =
        jpa.findByCountryAndIncomeTypeAndEffectiveEndIsNull(country, incomeType)?.toDomain()

    override fun findEffective(country: String, incomeType: IncomeType, date: LocalDate): TaxRate? =
        jpa.findEffective(country, incomeType, date)?.toDomain()

    override fun save(taxRate: TaxRate): TaxRate =
        jpa.save(TaxRateEntity.from(taxRate)).toDomain()
}
