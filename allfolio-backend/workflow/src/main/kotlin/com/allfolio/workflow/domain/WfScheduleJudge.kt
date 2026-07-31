package com.allfolio.workflow.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * 하위단계 실행일 판정 (FR-STEP-006) — 순수 로직.
 * D: 매일(단계 holiday_except_yn이면 영업일만).
 * M: date_term(n번째, 음수=역산) × date_gb(S 달력일 / B 영업일) 규칙의 해당일에만.
 * Q: 분기 마지막 달(3·6·9·12월)에 M 규칙 적용.
 */
class WfScheduleJudge(private val bizDay: BizDayCalculator) {

    fun shouldRun(
        termGb: WfTermGb,
        holidayExcept: Boolean,
        dateTerm: Int?,
        dateGb: String?,
        date: LocalDate,
    ): Boolean = when (termGb) {
        WfTermGb.D -> !holidayExcept || bizDay.isBizDay(date)
        WfTermGb.M -> monthlyRunDate(YearMonth.from(date), dateTerm, dateGb) == date
        WfTermGb.Q -> date.monthValue % 3 == 0 && monthlyRunDate(YearMonth.from(date), dateTerm, dateGb) == date
    }

    /** 해당 월의 실행일 — 규칙 불완전(dateTerm/dateGb null)이면 null(실행 안 함). */
    fun monthlyRunDate(ym: YearMonth, dateTerm: Int?, dateGb: String?): LocalDate? {
        if (dateTerm == null || dateTerm == 0 || dateGb == null) return null
        return when (dateGb) {
            "B" -> bizDay.nthBizDayOfMonth(ym, dateTerm)
            "S" -> {
                val len = ym.lengthOfMonth()
                val day = if (dateTerm > 0) dateTerm else len + dateTerm + 1
                if (day in 1..len) ym.atDay(day) else null
            }
            else -> null
        }
    }
}
