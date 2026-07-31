package com.allfolio.workflow.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class WfScheduleJudgeTest {

    // 2026-08: 15(토)·16(일)·17(월,휴일) — 마지막 영업일 31(월)
    private val calc = BizDayCalculator(setOf(LocalDate.of(2026, 8, 17)))
    private val judge = WfScheduleJudge(calc)

    @Test
    fun `D 주기는 매일 실행 - 휴일제외 단계는 영업일만`() {
        assertTrue(judge.shouldRun(WfTermGb.D, holidayExcept = false, dateTerm = null, dateGb = null, LocalDate.of(2026, 8, 15)))
        assertFalse(judge.shouldRun(WfTermGb.D, holidayExcept = true, dateTerm = null, dateGb = null, LocalDate.of(2026, 8, 15)))
        assertTrue(judge.shouldRun(WfTermGb.D, holidayExcept = true, dateTerm = null, dateGb = null, LocalDate.of(2026, 8, 18)))
    }

    @Test
    fun `M 주기 - 영업일 규칙(B)은 월말 역산 포함 해당일에만 실행`() {
        // -1B = 8월 마지막 영업일 = 31(월)
        assertTrue(judge.shouldRun(WfTermGb.M, false, dateTerm = -1, dateGb = "B", LocalDate.of(2026, 8, 31)))
        assertFalse(judge.shouldRun(WfTermGb.M, false, dateTerm = -1, dateGb = "B", LocalDate.of(2026, 8, 28)))
        // 1B = 첫 영업일 = 3(월)
        assertTrue(judge.shouldRun(WfTermGb.M, false, dateTerm = 1, dateGb = "B", LocalDate.of(2026, 8, 3)))
    }

    @Test
    fun `M 주기 - 달력일 규칙(S)은 n일·월말 역산`() {
        assertTrue(judge.shouldRun(WfTermGb.M, false, dateTerm = 5, dateGb = "S", LocalDate.of(2026, 8, 5)))
        // -1S = 월 마지막 날 = 31
        assertTrue(judge.shouldRun(WfTermGb.M, false, dateTerm = -1, dateGb = "S", LocalDate.of(2026, 8, 31)))
        assertFalse(judge.shouldRun(WfTermGb.M, false, dateTerm = -1, dateGb = "S", LocalDate.of(2026, 8, 30)))
    }

    @Test
    fun `M 주기 - 규칙이 없으면 실행하지 않는다`() {
        assertFalse(judge.shouldRun(WfTermGb.M, false, dateTerm = null, dateGb = null, LocalDate.of(2026, 8, 31)))
    }

    @Test
    fun `Q 주기 - 분기 마지막 달에만 M 규칙 적용`() {
        // 2026-09 마지막 달력일 = 30
        assertTrue(judge.shouldRun(WfTermGb.Q, false, dateTerm = -1, dateGb = "S", LocalDate.of(2026, 9, 30)))
        assertFalse(judge.shouldRun(WfTermGb.Q, false, dateTerm = -1, dateGb = "S", LocalDate.of(2026, 8, 31)))
    }
}
