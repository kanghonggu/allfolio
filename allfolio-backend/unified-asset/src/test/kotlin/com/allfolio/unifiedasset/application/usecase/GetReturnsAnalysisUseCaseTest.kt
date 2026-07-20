package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.UserBenchmarkLookup
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

class GetReturnsAnalysisUseCaseTest {

    private val userId = UUID.randomUUID()

    private class FakeNavSource(private val points: List<NavPoint>) : NavHistorySource {
        override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate) =
            points.filter { it.date in from..to }
    }

    private class FakeCashFlowRepo(private val flows: List<CashFlow> = emptyList()) : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) {}
    }

    private class FakeBenchmarkStore(private val rows: List<Pair<LocalDate, BigDecimal>> = emptyList()) : BenchmarkDailyStore {
        override fun latestDate(type: BenchmarkType): LocalDate? = rows.maxOfOrNull { it.first }
        override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) {}
        override fun series(type: BenchmarkType, from: LocalDate, to: LocalDate) =
            rows.filter { it.first in from..to }
    }

    private class FakeUserBenchmark(private val type: BenchmarkType?) : UserBenchmarkLookup {
        override fun get(userId: UUID): BenchmarkType? = type
    }

    private fun nav(day: Int, v: String) = NavPoint(LocalDate.of(2026, 6, day), BigDecimal(v))

    private fun useCase(
        navs: List<NavPoint>,
        bmType: BenchmarkType? = null,
        bmRows: List<Pair<LocalDate, BigDecimal>> = emptyList(),
    ) = GetReturnsAnalysisUseCase(
        FakeNavSource(navs), FakeCashFlowRepo(), FakeUserBenchmark(bmType), FakeBenchmarkStore(bmRows),
    )

    @Test
    fun `analyzes arbitrary range`() {
        val useCase = useCase(listOf(nav(1, "1000"), nav(30, "1100")))
        val result = useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))

        assertEquals(LocalDate.of(2026, 6, 30), result.asOfDate)
        assertEquals(2, result.navSeries.size)
        assertEquals(
            0,
            BigDecimal("0.1").compareTo(result.summary.twr!!.setScale(1, RoundingMode.HALF_UP)),
        )
    }

    @Test
    fun `insufficient observations throw`() {
        val useCase = useCase(listOf(nav(1, "1000")))
        assertThrows(InsufficientDataException::class.java) {
            useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
        }
    }

    @Test
    fun `from after to is rejected`() {
        val useCase = useCase(emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            useCase.analyze(userId, LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1))
        }
    }

    @Test
    fun `benchmark comparison when configured`() {
        // 포트폴리오 1000→1100 (+10%), BM 100→105 (+5%) → 초과 +5%p, 정규화 시계열은 startNav 기준
        val useCase = useCase(
            navs = listOf(nav(1, "1000"), nav(30, "1100")),
            bmType = BenchmarkType.SPX,
            bmRows = listOf(
                LocalDate.of(2026, 6, 1) to BigDecimal("100"),
                LocalDate.of(2026, 6, 30) to BigDecimal("105"),
            ),
        )
        val result = useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))

        val bm = result.benchmark
        assertNotNull(bm)
        assertEquals("SPX", bm!!.indexType)
        assertEquals(0, BigDecimal("0.05").compareTo(bm.periodReturn!!.setScale(2, RoundingMode.HALF_UP)))
        assertEquals(0, BigDecimal("0.05").compareTo(bm.excessReturn!!.setScale(2, RoundingMode.HALF_UP)))
        assertEquals(2, bm.series.size)
        assertEquals(0, BigDecimal("1000").compareTo(bm.series.first().nav))
        assertEquals(0, BigDecimal("1050").compareTo(bm.series.last().nav.setScale(0, RoundingMode.HALF_UP)))
    }

    @Test
    fun `no benchmark when not configured`() {
        val useCase = useCase(listOf(nav(1, "1000"), nav(30, "1100")))
        val result = useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
        assertNull(result.benchmark)
    }

    @Test
    fun `no benchmark when data insufficient`() {
        val useCase = useCase(
            navs = listOf(nav(1, "1000"), nav(30, "1100")),
            bmType = BenchmarkType.KOSPI,
            bmRows = listOf(LocalDate.of(2026, 6, 1) to BigDecimal("2600")),
        )
        val result = useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
        assertNull(result.benchmark)
    }
}
