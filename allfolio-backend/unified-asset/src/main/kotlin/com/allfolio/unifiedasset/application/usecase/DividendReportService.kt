package com.allfolio.unifiedasset.application.usecase

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class DividendReport(
    val userId: UUID,
    val period: String,
    val generatedAt: LocalDateTime,
    val totalDividend: BigDecimal,
    val receiptCount: Int,
    val monthlyAvg: BigDecimal,
    val annualProjected: BigDecimal,
    val monthlySeries: List<MonthlyDividend>,
    val bySymbol: List<SymbolDividend>,
    val recentHistory: List<DividendEntry>,
)

data class MonthlyDividend(
    val month: String,       // "2025-04"
    val amount: BigDecimal,
)

data class SymbolDividend(
    val stockName: String,
    val symbol: String?,
    val totalAmount: BigDecimal,
    val receiptCount: Int,
    val lastReceivedAt: LocalDate,
    val pct: BigDecimal,     // totalAmount / totalDividend * 100
)

data class DividendEntry(
    val tradedAt: LocalDate,
    val stockName: String,
    val symbol: String?,
    val amount: BigDecimal,
    val memo: String?,
)

@Service
class DividendReportService(private val jdbc: JdbcTemplate) {

    @Transactional(readOnly = true)
    fun report(userId: UUID, period: String): DividendReport {
        val since = periodStart(period)
        val sinceParam: LocalDate = since ?: LocalDate.of(2000, 1, 1)

        val whereClause = if (since != null)
            "WHERE user_id = ? AND trade_type = 'DIVIDEND' AND traded_at >= ?"
        else
            "WHERE user_id = ? AND trade_type = 'DIVIDEND'"

        // 1. KPI 집계
        data class KpiRow(val total: BigDecimal, val count: Int)
        val kpi = runCatching {
            jdbc.query(
                "SELECT COALESCE(SUM(total_amount),0) AS total, COUNT(*) AS cnt FROM ua_stock_trades $whereClause",
                { rs, _ -> KpiRow(rs.getBigDecimal("total"), rs.getInt("cnt")) },
                *if (since != null) arrayOf(userId, sinceParam) else arrayOf(userId),
            ).firstOrNull() ?: KpiRow(BigDecimal.ZERO, 0)
        }.getOrElse { KpiRow(BigDecimal.ZERO, 0) }

        // 2. 월별 합계
        val monthlySeries = runCatching {
            jdbc.query(
                """SELECT TO_CHAR(traded_at, 'YYYY-MM') AS month,
                          SUM(total_amount) AS amount
                   FROM ua_stock_trades $whereClause
                   GROUP BY TO_CHAR(traded_at, 'YYYY-MM')
                   ORDER BY 1""",
                { rs, _ -> MonthlyDividend(rs.getString("month"), rs.getBigDecimal("amount")) },
                *if (since != null) arrayOf(userId, sinceParam) else arrayOf(userId),
            )
        }.getOrElse { emptyList() }

        // 3. 종목별 합계
        val bySymbol = runCatching {
            val rows = jdbc.query(
                """SELECT stock_name, symbol,
                          SUM(total_amount) AS total, COUNT(*) AS cnt,
                          MAX(traded_at) AS last_at
                   FROM ua_stock_trades $whereClause
                   GROUP BY stock_name, symbol
                   ORDER BY total DESC""",
                { rs, _ -> Triple(
                    rs.getString("stock_name"),
                    Triple(rs.getString("symbol"), rs.getBigDecimal("total"), rs.getInt("cnt")),
                    rs.getDate("last_at").toLocalDate(),
                )},
                *if (since != null) arrayOf(userId, sinceParam) else arrayOf(userId),
            )
            rows.map { (name, data, lastAt) ->
                val (sym, total, cnt) = data
                val pct = if (kpi.total > BigDecimal.ZERO)
                    total.divide(kpi.total, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
                else BigDecimal.ZERO
                SymbolDividend(name, sym, total, cnt, lastAt, pct)
            }
        }.getOrElse { emptyList() }

        // 4. 최근 이력
        val recentHistory = runCatching {
            jdbc.query(
                """SELECT traded_at, stock_name, symbol, total_amount, memo
                   FROM ua_stock_trades $whereClause
                   ORDER BY traded_at DESC LIMIT 30""",
                { rs, _ -> DividendEntry(
                    rs.getDate("traded_at").toLocalDate(),
                    rs.getString("stock_name"),
                    rs.getString("symbol"),
                    rs.getBigDecimal("total_amount"),
                    rs.getString("memo"),
                )},
                *if (since != null) arrayOf(userId, sinceParam) else arrayOf(userId),
            )
        }.getOrElse { emptyList() }

        // 5. 연환산 예상 (항상 최근 12개월, period 탭과 무관)
        val annualProjected = runCatching {
            jdbc.query(
                """SELECT COALESCE(SUM(total_amount),0) AS total
                   FROM ua_stock_trades
                   WHERE user_id = ? AND trade_type = 'DIVIDEND' AND traded_at >= ?""",
                { rs, _ -> rs.getBigDecimal("total") },
                userId, LocalDate.now().minusYears(1),
            ).firstOrNull() ?: BigDecimal.ZERO
        }.getOrElse { BigDecimal.ZERO }

        // 6. 월 평균: totalDividend / 기간 개월수 (최소 1)
        val elapsedMonths = if (since != null) {
            java.time.temporal.ChronoUnit.MONTHS.between(since, LocalDate.now()).coerceAtLeast(1)
        } else {
            val oldest = recentHistory.minByOrNull { it.tradedAt }?.tradedAt
            if (oldest != null)
                java.time.temporal.ChronoUnit.MONTHS.between(oldest, LocalDate.now()).coerceAtLeast(1)
            else 1L
        }
        val monthlyAvg = kpi.total.divide(BigDecimal(elapsedMonths), 0, RoundingMode.HALF_UP)

        return DividendReport(
            userId = userId,
            period = period,
            generatedAt = LocalDateTime.now(),
            totalDividend = kpi.total,
            receiptCount = kpi.count,
            monthlyAvg = monthlyAvg,
            annualProjected = annualProjected,
            monthlySeries = monthlySeries,
            bySymbol = bySymbol,
            recentHistory = recentHistory,
        )
    }

    private fun periodStart(period: String): LocalDate? = when (period) {
        "YTD" -> LocalDate.of(LocalDate.now().year, 1, 1)
        "1Y"  -> LocalDate.now().minusYears(1)
        else  -> null   // 전체
    }
}
