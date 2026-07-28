package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.CostLedgerSource
import com.allfolio.unifiedasset.application.port.CostRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcCostLedgerSource(private val jdbc: JdbcTemplate) : CostLedgerSource {

    override fun findCosts(userId: UUID, from: LocalDate, to: LocalDate): List<CostRecord> =
        jdbc.query(
            """SELECT t.traded_at, t.stock_name, t.symbol, t.trade_type, t.fee, t.tax,
                      a.account_name, a.provider
               FROM ua_stock_trades t
               JOIN ua_accounts a ON a.id = t.account_id
               WHERE t.user_id = ? AND t.trade_type <> 'DIVIDEND'
                 AND (t.fee > 0 OR t.tax > 0)
                 AND t.traded_at >= ? AND t.traded_at <= ?
               ORDER BY t.traded_at ASC""",
            { rs, _ ->
                CostRecord(
                    tradeDate = rs.getDate("traded_at").toLocalDate(),
                    stockName = rs.getString("stock_name"),
                    symbol = rs.getString("symbol"),
                    accountName = rs.getString("account_name"),
                    provider = rs.getString("provider"),
                    tradeType = rs.getString("trade_type"),
                    fee = rs.getBigDecimal("fee"),
                    tax = rs.getBigDecimal("tax"),
                )
            },
            userId, from, to,
        )
}
