package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface CashFlowJpaRepository : JpaRepository<CashFlowEntity, UUID> {
    fun findByUserIdOrderByFlowDateDesc(userId: UUID): List<CashFlowEntity>
    fun findByUserIdAndFlowDateBetweenOrderByFlowDateDesc(
        userId: UUID, from: LocalDate, to: LocalDate,
    ): List<CashFlowEntity>
}
