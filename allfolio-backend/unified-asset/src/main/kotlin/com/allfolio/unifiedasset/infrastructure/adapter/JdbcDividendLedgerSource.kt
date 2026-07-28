package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.DividendLedgerSource
import com.allfolio.unifiedasset.application.port.DividendRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcDividendLedgerSource(private val jdbc: JdbcTemplate) : DividendLedgerSource {

    override fun findDividends(userId: UUID, from: LocalDate, to: LocalDate): List<DividendRecord> =
        jdbc.query(
            """SELECT t.traded_at, t.stock_name, t.symbol, t.total_amount, t.tax,
                      a.account_name, a.provider
               FROM ua_stock_trades t
               JOIN ua_accounts a ON a.id = t.account_id
               WHERE t.user_id = ? AND t.trade_type = 'DIVIDEND'
                 AND t.traded_at >= ? AND t.traded_at <= ?
               ORDER BY t.traded_at ASC""",
            { rs, _ ->
                DividendRecord(
                    payDate = rs.getDate("traded_at").toLocalDate(),
                    stockName = rs.getString("stock_name"),
                    symbol = rs.getString("symbol"),
                    accountName = rs.getString("account_name"),
                    provider = rs.getString("provider"),
                    gross = rs.getBigDecimal("total_amount"),
                    tax = rs.getBigDecimal("tax"),
                )
            },
            userId, from, to,
        )
}
