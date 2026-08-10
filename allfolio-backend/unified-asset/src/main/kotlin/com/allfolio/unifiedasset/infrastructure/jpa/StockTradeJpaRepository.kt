package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.StockTradeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface StockTradeJpaRepository : JpaRepository<StockTradeEntity, UUID> {
    fun findByAccountIdOrderByTradedAtDescCreatedAtDesc(accountId: UUID): List<StockTradeEntity>

    @Modifying
    @Query("DELETE FROM StockTradeEntity t WHERE t.accountId = :accountId")
    fun deleteByAccountId(accountId: UUID)
}
