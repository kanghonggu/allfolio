package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.NavFxPoint
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.UserBenchmarkLookup
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        override fun deleteByAccountId(accountId: UUID) {}
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

    /** AF-106 자산/환율 분해용 시계열 — 기본값은 빈 시계열이라 currencyAttribution은 null */
    private class FakeNavFxSource(
        private val points: List<NavFxPoint> = emptyList(),
        private val currencies: List<String> = emptyList(),
    ) : NavFxHistorySource {
        override fun navFxSeries(userId: UUID, from: LocalDate, to: LocalDate) =
            points.filter { it.date in from..to }

        override fun currenciesIn(userId: UUID, from: LocalDate, to: LocalDate) = currencies
    }

    private fun nav(day: Int, v: String) = NavPoint(LocalDate.of(2026, 6, day), BigDecimal(v))

    private fun navFx(day: Int, v: String, frozen: String?) =
        NavFxPoint(LocalDate.of(2026, 6, day), BigDecimal(v), frozen?.let { BigDecimal(it) })

    private fun useCase(
        navs: List<NavPoint>,
        bmType: BenchmarkType? = null,
        bmRows: List<Pair<LocalDate, BigDecimal>> = emptyList(),
        fxPoints: List<NavFxPoint> = emptyList(),
        currencies: List<String> = emptyList(),
    ) = GetReturnsAnalysisUseCase(
        FakeNavSource(navs), FakeCashFlowRepo(), FakeUserBenchmark(bmType), FakeBenchmarkStore(bmRows),
        FakeNavFxSource(fxPoints, currencies),
    )

    @Test
    fun `analyzes arbitrary range`() {
        val useCase = useCase(listOf(nav(1, "1000"), nav(30, "1100")))
        val result = useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))

        assertEquals(LocalDate.of(2026, 6, 30), result.asOfDate)
        assertEquals(2, result.navSeries.size)
        // 도메인 결과는 ratio(0~1) — percent 변환은 ReportController API 경계에서 (QA 후속 #1)
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
        // 포트폴리오 1000→1100 (+10%), BM 100→105 (+5%) → 초과 +5%p (ratio 단위), 정규화 시계열은 startNav 기준
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

    // --- AF-106 자산/환율 분해 노출 규칙 ---

    private val navs = listOf(nav(1, "1000"), nav(30, "1100"))

    private fun analyzeJune(useCase: GetReturnsAnalysisUseCase) =
        useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))

    @Test
    fun `분해 관측이 1건이면 외화가 있어도 노출하지 않는다`() {
        val result = analyzeJune(
            useCase(navs, fxPoints = listOf(navFx(1, "1000", null)), currencies = listOf("KRW", "USD")),
        )
        assertNull(result.currencyAttribution)
    }

    @Test
    fun `원화만 보유하면 관측이 충분해도 노출하지 않는다`() {
        // 이 테스트가 노출 규칙의 `&&`를 붙잡는다 — `||`로 바꾸면 여기가 깨진다
        val result = analyzeJune(
            useCase(
                navs,
                fxPoints = listOf(navFx(1, "1000", null), navFx(30, "1100", "1050")),
                currencies = listOf("KRW"),
            ),
        )
        assertNull(result.currencyAttribution)
    }

    @Test
    fun `관측 2건 이상 + 외화 보유면 자산-환율로 분해한다`() {
        // 1000 → 1100 (+10%), 직전 환율 고정 시 1050 (자산 +5%) → 환율 기여 1.10/1.05 − 1
        val result = analyzeJune(
            useCase(
                navs,
                fxPoints = listOf(navFx(1, "1000", null), navFx(30, "1100", "1050")),
                currencies = listOf("USD", "KRW"),
            ),
        )

        val attribution = result.currencyAttribution
        assertNotNull(attribution)
        // 도메인은 ratio(0~1) — percent 변환은 ReportController 한 곳뿐
        assertEquals(0, BigDecimal("0.05").compareTo(attribution!!.assetContribution.setScale(2, RoundingMode.HALF_UP)))
        assertEquals(0, BigDecimal("0.05").compareTo(attribution.fxContribution.setScale(2, RoundingMode.HALF_UP)))
        // 외화만 남고 KRW는 걸러진다 — "원화 대비" 분해라 KRW를 세면 항상 참인 값이 된다
        assertTrue(attribution.currencies.contains("USD"))
        assertFalse(attribution.currencies.contains("KRW"))
    }

    @Test
    fun `분해가 불가능하면 노출하지 않는다`() {
        // navAtPriorFx 결측 — 억지로 이으면 환율 차이가 자산 쪽에 흡수된다
        val result = analyzeJune(
            useCase(
                navs,
                fxPoints = listOf(navFx(1, "1000", null), navFx(30, "1100", null)),
                currencies = listOf("USD"),
            ),
        )
        assertNull(result.currencyAttribution)
    }

    @Test
    fun `분해 시계열이 없으면 노출하지 않는다`() {
        assertNull(analyzeJune(useCase(navs)).currencyAttribution)
    }
}
