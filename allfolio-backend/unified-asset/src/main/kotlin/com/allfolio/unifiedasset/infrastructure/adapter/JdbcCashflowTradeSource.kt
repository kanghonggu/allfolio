package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.CashflowTradeSource
import com.allfolio.unifiedasset.application.port.TradeCashRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcCashflowTradeSource(private val jdbc: JdbcTemplate) : CashflowTradeSource {

    override fun findTrades(userId: UUID, from: LocalDate, to: LocalDate): List<TradeCashRecord> =
        jdbc.query(
            """SELECT t.traded_at, t.trade_type, t.stock_name, t.total_amount, t.fee, t.tax,
                      a.account_name
               FROM ua_stock_trades t
               JOIN ua_accounts a ON a.id = t.account_id
               WHERE t.user_id = ? AND t.traded_at >= ? AND t.traded_at <= ?
               ORDER BY t.traded_at ASC""",
            { rs, _ ->
                TradeCashRecord(
                    tradeDate = rs.getDate("traded_at").toLocalDate(),
                    tradeType = rs.getString("trade_type"),
                    stockName = rs.getString("stock_name"),
                    accountName = rs.getString("account_name"),
                    totalAmount = rs.getBigDecimal("total_amount"),
                    fee = rs.getBigDecimal("fee"),
                    tax = rs.getBigDecimal("tax"),
                )
            },
            userId, from, to,
        )
}
