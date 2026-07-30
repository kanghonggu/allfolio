package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
import java.time.LocalDate

interface TaxRateRepository {
    fun findAll(): List<TaxRate>
    fun findOpen(country: String, incomeType: IncomeType): TaxRate?
    fun findEffective(country: String, incomeType: IncomeType, date: LocalDate): TaxRate?
    fun save(taxRate: TaxRate): TaxRate
}
