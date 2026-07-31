package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.DividendRecord
import java.math.BigDecimal
import java.time.LocalDate

data class DividendCalendarEntry(
    val symbol: String?,
    val stockName: String,
    val cadence: String,
    val paidMonths: List<Int>,     // 1~12, 오름차순 distinct
    val payCount: Int,             // TTM 지급 횟수
    val lastPayDate: LocalDate,
    val ttmNet: BigDecimal,
)

object DividendCalendarCalculator {
    /** 최근 12개월 배당 이력 → 종목별 지급 패턴(사실형). 예측/추정 없음. */
    fun build(ttm: List<DividendRecord>): List<DividendCalendarEntry> {
        return ttm.groupBy { it.stockName to it.symbol }
            .map { (key, rs) ->
                val months = rs.map { it.payDate.monthValue }.distinct().sorted()
                val count = rs.size
                DividendCalendarEntry(
                    symbol = key.second,
                    stockName = key.first,
                    cadence = cadenceOf(count),
                    paidMonths = months,
                    payCount = count,
                    lastPayDate = rs.maxOf { it.payDate },
                    ttmNet = rs.fold(BigDecimal.ZERO) { a, r -> a + r.net },
                )
            }
            .sortedByDescending { it.ttmNet }
    }

    private fun cadenceOf(count: Int): String = when {
        count >= 10 -> "월배당"
        count == 4  -> "분기배당"
        count == 2  -> "반기배당"
        count == 1  -> "연 1회/단발"
        else        -> "비정기"
    }
}
