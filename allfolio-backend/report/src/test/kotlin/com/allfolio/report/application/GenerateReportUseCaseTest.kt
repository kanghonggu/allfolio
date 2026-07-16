package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportStatus
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.report.domain.archive.ReportWarning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class GenerateReportUseCaseTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val asOf = LocalDate.of(2026, 6, 30)

    private class FakeGenerator(override val type: ReportType, private val asOf: LocalDate) : ReportBodyGenerator {
        override fun generate(userId: UUID, period: ReportPeriod) =
            GeneratedReport(asOfDate = asOf, bodyJson = """{"nav":100}""")
    }

    private class FakeGate(private val warnings: List<ReportWarning> = emptyList()) : ReportValidationGate {
        override fun check(userId: UUID, period: ReportPeriod) = warnings
    }

    private class InMemoryRepo : ReportArchiveRepository {
        val stored = mutableMapOf<String, ReportArchive>()
        override fun upsert(archive: ReportArchive): ReportArchive {
            stored["${archive.userId}-${archive.type}-${archive.period}"] = archive
            return archive
        }
        override fun findById(id: UUID) = stored.values.firstOrNull { it.id == id }
        override fun findAll(userId: UUID, type: ReportType?) =
            stored.values.filter { it.userId == userId && (type == null || it.type == type) }
    }

    @Test
    fun `generates FINAL report and archives it`() {
        val repo = InMemoryRepo()
        val useCase = GenerateReportUseCase(listOf(FakeGenerator(ReportType.RETURNS, asOf)), FakeGate(), repo)

        val result = useCase.generate(userId, ReportType.RETURNS, period)

        assertEquals(ReportStatus.FINAL, result.status)
        assertEquals(asOf, result.asOfDate)
        assertEquals(1, repo.stored.size)
    }

    @Test
    fun `gate warnings produce WARNING report`() {
        val repo = InMemoryRepo()
        val gate = FakeGate(listOf(ReportWarning("SYNC_ERROR", "동기화 실패")))
        val useCase = GenerateReportUseCase(listOf(FakeGenerator(ReportType.RETURNS, asOf)), gate, repo)

        val result = useCase.generate(userId, ReportType.RETURNS, period)

        assertEquals(ReportStatus.WARNING, result.status)
    }

    @Test
    fun `unsupported type throws`() {
        val useCase = GenerateReportUseCase(emptyList(), FakeGate(), InMemoryRepo())
        assertThrows(UnsupportedReportTypeException::class.java) {
            useCase.generate(userId, ReportType.COST, period)
        }
    }

    @Test
    fun `regenerating same period keeps single archive`() {
        val repo = InMemoryRepo()
        val useCase = GenerateReportUseCase(listOf(FakeGenerator(ReportType.RETURNS, asOf)), FakeGate(), repo)

        useCase.generate(userId, ReportType.RETURNS, period)
        useCase.generate(userId, ReportType.RETURNS, period)

        assertEquals(1, repo.stored.size)
    }
}
