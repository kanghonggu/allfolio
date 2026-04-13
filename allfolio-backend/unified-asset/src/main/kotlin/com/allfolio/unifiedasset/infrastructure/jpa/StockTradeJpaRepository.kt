package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.StockTradeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StockTradeJpaRepository : JpaRepository<StockTradeEntity, UUID> {
    fun findByAccountIdOrderByTradedAtDescCreatedAtDesc(accountId: UUID): List<StockTradeEntity>
}
