package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.util.UUID

interface CashFlowJpaRepository : JpaRepository<CashFlowEntity, UUID> {
    fun findByUserIdOrderByFlowDateDesc(userId: UUID): List<CashFlowEntity>
    fun findByUserIdAndFlowDateBetweenOrderByFlowDateDesc(
        userId: UUID, from: LocalDate, to: LocalDate,
    ): List<CashFlowEntity>

    @Modifying
    @Query("DELETE FROM CashFlowEntity c WHERE c.accountId = :accountId")
    fun deleteByAccountId(accountId: UUID)
}
