package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.infrastructure.entity.StockTradeEntity
import com.allfolio.unifiedasset.infrastructure.jpa.StockTradeJpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class StockTradeRepositoryImpl(
    private val jpa: StockTradeJpaRepository,
) : StockTradeRepository {
    override fun save(trade: StockTrade): StockTrade =
        jpa.save(StockTradeEntity.fromDomain(trade)).toDomain()

    override fun findByAccountId(accountId: UUID): List<StockTrade> =
        jpa.findByAccountIdOrderByTradedAtDescCreatedAtDesc(accountId).map { it.toDomain() }

    override fun findById(id: UUID): StockTrade? =
        jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun delete(id: UUID) = jpa.deleteById(id)
}
