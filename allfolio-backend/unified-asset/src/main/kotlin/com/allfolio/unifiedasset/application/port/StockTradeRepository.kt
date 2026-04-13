package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.account.StockTrade
import java.util.UUID

interface StockTradeRepository {
    fun save(trade: StockTrade): StockTrade
    fun findByAccountId(accountId: UUID): List<StockTrade>
    fun findById(id: UUID): StockTrade?
    fun delete(id: UUID)
}
