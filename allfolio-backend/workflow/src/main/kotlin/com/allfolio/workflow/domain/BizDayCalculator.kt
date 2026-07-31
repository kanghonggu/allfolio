package com.allfolio.workflow.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 영업일 계산 유틸 (P3 #25, FR-CMMN-002·FR-STEP-006) — 순수 로직.
 * 영업일 = 주말(토·일)이 아니고 휴일 테이블(wf_holiday)에 없는 날.
 * 휴일 집합은 호출 측(서비스)이 조회해 주입한다.
 */
class BizDayCalculator(private val holidays: Set<LocalDate>) {

    fun isBizDay(date: LocalDate): Boolean =
        date.dayOfWeek !in WEEKEND && date !in holidays

    /** n영업일 이동 — 양수 순방향·음수 역산·0은 그대로. */
    fun addBizDays(date: LocalDate, n: Int): LocalDate {
        if (n == 0) return date
        val step = if (n > 0) 1L else -1L
        var remaining = kotlin.math.abs(n)
        var cursor = date
        while (remaining > 0) {
            cursor = cursor.plusDays(step)
            if (isBizDay(cursor)) remaining--
        }
        return cursor
    }

    /**
     * 해당 월의 n번째 영업일 — 양수는 월초부터, 음수는 월말에서 역산(-1 = 마지막 영업일).
     * n=0 또는 월 영업일 수 초과 시 null (sub_step 실행일 산정에서 "해당 없음").
     */
    fun nthBizDayOfMonth(ym: YearMonth, n: Int): LocalDate? {
        if (n == 0) return null
        val bizDays = (1..ym.lengthOfMonth())
            .map { ym.atDay(it) }
            .filter { isBizDay(it) }
        if (bizDays.isEmpty() || kotlin.math.abs(n) > bizDays.size) return null
        return if (n > 0) bizDays[n - 1] else bizDays[bizDays.size + n]
    }

    companion object {
        private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
