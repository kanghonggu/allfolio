package com.allfolio.workflow.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

class BizDayCalculatorTest {

    // 2026-08: 1(토) 2(일) 3(월)… 15(토, 광복절) 17(월, 대체공휴일 가정) … 31(월)
    private val calc = BizDayCalculator(
        holidays = setOf(LocalDate.of(2026, 8, 17)),
    )

    @Test
    fun `주말과 휴일은 영업일이 아니다`() {
        assertFalse(calc.isBizDay(LocalDate.of(2026, 8, 1)))   // 토
        assertFalse(calc.isBizDay(LocalDate.of(2026, 8, 2)))   // 일
        assertFalse(calc.isBizDay(LocalDate.of(2026, 8, 17)))  // 휴일
        assertTrue(calc.isBizDay(LocalDate.of(2026, 8, 3)))    // 월
    }

    @Test
    fun `addBizDays - 순방향은 주말·휴일을 건너뛴다`() {
        // 8/14(금) +1영업일 → 15(토)·16(일)·17(휴일) 건너뛰고 18(화)
        assertEquals(LocalDate.of(2026, 8, 18), calc.addBizDays(LocalDate.of(2026, 8, 14), 1))
    }

    @Test
    fun `addBizDays - 역산은 음수로`() {
        // 8/18(화) -1영업일 → 17(휴)·16(일)·15(토) 건너뛰고 14(금)
        assertEquals(LocalDate.of(2026, 8, 14), calc.addBizDays(LocalDate.of(2026, 8, 18), -1))
    }

    @Test
    fun `addBizDays - 0이면 그대로`() {
        assertEquals(LocalDate.of(2026, 8, 18), calc.addBizDays(LocalDate.of(2026, 8, 18), 0))
    }

    @Test
    fun `nthBizDayOfMonth - 양수는 앞에서, 음수는 월말에서 역산`() {
        // 2026-08 첫 영업일 = 3(월), 둘째 = 4(화)
        assertEquals(LocalDate.of(2026, 8, 3), calc.nthBizDayOfMonth(YearMonth.of(2026, 8), 1))
        assertEquals(LocalDate.of(2026, 8, 4), calc.nthBizDayOfMonth(YearMonth.of(2026, 8), 2))
        // 마지막 영업일 = 31(월), 역산 2번째 = 28(금)
        assertEquals(LocalDate.of(2026, 8, 31), calc.nthBizDayOfMonth(YearMonth.of(2026, 8), -1))
        assertEquals(LocalDate.of(2026, 8, 28), calc.nthBizDayOfMonth(YearMonth.of(2026, 8), -2))
    }

    @Test
    fun `nthBizDayOfMonth - 범위 초과·0은 null`() {
        assertNull(calc.nthBizDayOfMonth(YearMonth.of(2026, 8), 0))
        assertNull(calc.nthBizDayOfMonth(YearMonth.of(2026, 8), 99))
        assertNull(calc.nthBizDayOfMonth(YearMonth.of(2026, 8), -99))
    }
}
