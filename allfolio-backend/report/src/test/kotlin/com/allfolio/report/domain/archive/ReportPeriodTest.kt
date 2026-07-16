package com.allfolio.report.domain.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReportPeriodTest {

    @Test
    fun `monthly creates first to last day of month`() {
        val period = ReportPeriod.monthly(2026, 6)
        assertEquals(LocalDate.of(2026, 6, 1), period.start)
        assertEquals(LocalDate.of(2026, 6, 30), period.end)
    }

    @Test
    fun `start after end is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReportPeriod(LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1))
        }
    }
}
