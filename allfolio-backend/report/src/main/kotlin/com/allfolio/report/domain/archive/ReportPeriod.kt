package com.allfolio.report.domain.archive

import java.time.LocalDate
import java.time.YearMonth

data class ReportPeriod(val start: LocalDate, val end: LocalDate) {
    init {
        require(!start.isAfter(end)) { "기간 시작일이 종료일 이후일 수 없습니다: $start > $end" }
    }

    companion object {
        fun monthly(year: Int, month: Int): ReportPeriod {
            val ym = YearMonth.of(year, month)
            return ReportPeriod(ym.atDay(1), ym.atEndOfMonth())
        }
    }
}
