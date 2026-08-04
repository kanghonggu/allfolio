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

    // saveAndFlush: 버저닝 등록 시 기존 open 행 마감(UPDATE)이 새 open 행(INSERT)보다
    // 먼저 DB에 반영되도록 강제. save만 쓰면 Hibernate가 order_inserts로 INSERT를 먼저
    // flush해 partial unique index(uk_tax_rates_open)를 순간 위반 → DataIntegrity(422).
    override fun save(taxRate: TaxRate): TaxRate =
        jpa.saveAndFlush(TaxRateEntity.from(taxRate)).toDomain()
}
