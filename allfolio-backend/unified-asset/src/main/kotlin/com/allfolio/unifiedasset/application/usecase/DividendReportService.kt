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
        TODO("implement")
    }

    private fun periodStart(period: String): LocalDate? = when (period) {
        "YTD" -> LocalDate.of(LocalDate.now().year, 1, 1)
        "1Y"  -> LocalDate.now().minusYears(1)
        else  -> null   // 전체
    }
}
