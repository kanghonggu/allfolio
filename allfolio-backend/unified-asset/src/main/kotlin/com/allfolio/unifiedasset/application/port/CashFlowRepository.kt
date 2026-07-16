package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import java.time.LocalDate
import java.util.UUID

interface CashFlowRepository {
    fun save(cashFlow: CashFlow): CashFlow
    fun findById(id: UUID): CashFlow?
    fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate): List<CashFlow>
    fun findByUserId(userId: UUID): List<CashFlow>
    fun delete(id: UUID)
}
