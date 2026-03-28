package com.allfolio.api.trade

import com.allfolio.trade.domain.TradeId
import java.util.UUID

data class TradeResponse(
    val tradeId: UUID,
) {
    companion object {
        fun from(tradeId: TradeId): TradeResponse = TradeResponse(tradeId.value)
    }
}
