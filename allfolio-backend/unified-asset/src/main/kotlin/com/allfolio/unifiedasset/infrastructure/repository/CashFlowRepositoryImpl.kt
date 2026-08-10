package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import com.allfolio.unifiedasset.infrastructure.jpa.CashFlowJpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Repository
class CashFlowRepositoryImpl(private val jpa: CashFlowJpaRepository) : CashFlowRepository {
    override fun save(cashFlow: CashFlow): CashFlow =
        jpa.save(CashFlowEntity.from(cashFlow)).toDomain()

    override fun findById(id: UUID): CashFlow? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate): List<CashFlow> =
        jpa.findByUserIdAndFlowDateBetweenOrderByFlowDateDesc(userId, from, to).map { it.toDomain() }

    override fun findByUserId(userId: UUID): List<CashFlow> =
        jpa.findByUserIdOrderByFlowDateDesc(userId).map { it.toDomain() }

    override fun delete(id: UUID) = jpa.deleteById(id)

    @Transactional
    override fun deleteByAccountId(accountId: UUID) =
        jpa.deleteByAccountId(accountId)
}
