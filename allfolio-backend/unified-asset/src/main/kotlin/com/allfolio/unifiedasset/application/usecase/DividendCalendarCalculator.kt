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
                DividendCalendarEntry(
                    symbol = key.second,
                    stockName = key.first,
                    // 주기는 "지급된 distinct 월 수" 기준 — 다계좌 보유로 레코드가 중복돼도
                    // 실제 지급 빈도(예: 분기=4개월)를 정확히 반영(paidMonths와 항상 일관).
                    cadence = cadenceOf(months.size),
                    paidMonths = months,
                    payCount = rs.size,
                    lastPayDate = rs.maxOf { it.payDate },
                    ttmNet = rs.fold(BigDecimal.ZERO) { a, r -> a + r.net },
                )
            }
            .sortedByDescending { it.ttmNet }
    }

    /** distinctMonths = TTM 동안 배당이 지급된 서로 다른 달의 수(1~12). */
    private fun cadenceOf(distinctMonths: Int): String = when {
        distinctMonths >= 10 -> "월배당"
        distinctMonths == 4  -> "분기배당"
        distinctMonths == 2  -> "반기배당"
        distinctMonths == 1  -> "연 1회/단발"
        else                 -> "비정기"
    }
}
