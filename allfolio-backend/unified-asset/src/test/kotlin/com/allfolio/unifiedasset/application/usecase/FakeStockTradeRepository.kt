package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.domain.account.StockTrade
import java.util.UUID

/** SyncAccountUseCase 테스트 공용 — 계좌별 거래 로그를 미리 심어 둔다 (AF-93). */
class FakeStockTradeRepository(
    private val trades: List<StockTrade> = emptyList(),
) : StockTradeRepository {
    override fun save(trade: StockTrade): StockTrade = trade
    override fun findByAccountId(accountId: UUID): List<StockTrade> = trades.filter { it.accountId == accountId }
    override fun findById(id: UUID): StockTrade? = trades.find { it.id == id }
    override fun delete(id: UUID) = Unit
}
