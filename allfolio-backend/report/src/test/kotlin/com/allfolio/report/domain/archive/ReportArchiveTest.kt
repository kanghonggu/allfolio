package com.allfolio.report.domain.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ReportArchiveTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)

    @Test
    fun `no warnings means FINAL`() {
        val archive = ReportArchive.create(
            userId = userId, type = ReportType.RETURNS, period = period,
            asOfDate = LocalDate.of(2026, 6, 30), warnings = emptyList(), bodyJson = """{"a":1}""",
        )
        assertEquals(ReportStatus.FINAL, archive.status)
    }

    @Test
    fun `warnings mean WARNING status`() {
        val archive = ReportArchive.create(
            userId = userId, type = ReportType.RETURNS, period = period,
            asOfDate = LocalDate.of(2026, 6, 30),
            warnings = listOf(ReportWarning("SYNC_ERROR", "계좌 동기화 실패")), bodyJson = """{"a":1}""",
        )
        assertEquals(ReportStatus.WARNING, archive.status)
        assertEquals(1, archive.warnings.size)
    }

    @Test
    fun `blank body is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReportArchive.create(
                userId = userId, type = ReportType.RETURNS, period = period,
                asOfDate = LocalDate.of(2026, 6, 30), warnings = emptyList(), bodyJson = " ",
            )
        }
    }
}
