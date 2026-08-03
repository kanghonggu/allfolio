package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ReportServiceTest {

    @Mock lateinit var assetRepository: AssetRepository
    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var jdbc: JdbcTemplate

    private val userId    = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    // 기존 집계 로직 검증용: USD를 1:1로 두는 항등 환산기 (환율 왜곡 없이 합산 로직만 확인).
    private val identityFx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String) = amount
    }

    private val emptyBenchmarkStore = object : com.allfolio.unifiedasset.application.port.BenchmarkDailyStore {
        override fun latestDate(type: com.allfolio.unifiedasset.domain.benchmark.BenchmarkType) = null
        override fun upsert(
            type: com.allfolio.unifiedasset.domain.benchmark.BenchmarkType,
            rows: List<Pair<java.time.LocalDate, BigDecimal>>,
        ) = Unit
        override fun series(
            type: com.allfolio.unifiedasset.domain.benchmark.BenchmarkType,
            from: java.time.LocalDate, to: java.time.LocalDate,
        ): List<Pair<java.time.LocalDate, BigDecimal>> = emptyList()
    }

    private fun svc(
        fx: FxConverter = identityFx,
        benchmarkStore: com.allfolio.unifiedasset.application.port.BenchmarkDailyStore = emptyBenchmarkStore,
    ) = ReportService(assetRepository, accountRepository, jdbc, fx, benchmarkStore)

    // ── summary ───────────────────────────────────────────────

    @Test
    fun `자산 없으면 summary - NAV 0, PnL 0, 카운트 0`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().summary(userId)

        assertEquals(BigDecimal.ZERO, result.nav)
        assertEquals(BigDecimal.ZERO, result.unrealizedPnl)
        assertEquals(BigDecimal.ZERO, result.unrealizedPnlPct)
        assertEquals(0, result.assetCount)
        assertEquals(0, result.accountCount)
        assertTrue(result.byType.isEmpty())
    }

    @Test
    fun `주식 1개 - summary NAV는 currentValue 합산`() {
        val asset = stock(purchasePrice = bd("50000"), quantity = bd("10"), currentValue = bd("600000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset))
        `when`(accountRepository.findByUserId(userId)).thenReturn(listOf(account()))

        val result = svc().summary(userId)

        assertEquals(bd("600000"), result.nav)
        assertEquals(bd("500000"), result.totalPurchaseCost)
        assertEquals(bd("100000"), result.unrealizedPnl)
        // unrealizedPnlPct = 100000/500000 * 100 = 20.00
        assertEquals(0, bd("20.00").compareTo(result.unrealizedPnlPct))
        assertEquals(1, result.assetCount)
        assertEquals(1, result.accountCount)
    }

    @Test
    fun `두 자산 - summary byType 그룹핑 확인`() {
        val stock1 = stock(currentValue = bd("300000"))
        val stock2 = stock(currentValue = bd("200000"))
        val crypto  = crypto(currentValue = bd("100000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(stock1, stock2, crypto))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().summary(userId)

        val types = result.byType.map { it.type }
        assertTrue(types.contains("STOCK"))
        assertTrue(types.contains("CRYPTO"))
        val stockBreakdown = result.byType.first { it.type == "STOCK" }
        assertEquals(2, stockBreakdown.count)
        assertEquals(0, bd("500000").compareTo(stockBreakdown.value))
    }

    @Test
    fun `매입원가 0이면 unrealizedPnlPct는 0`() {
        val asset = stock(purchasePrice = bd("0"), quantity = bd("0"), currentValue = bd("0"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().summary(userId)

        assertEquals(BigDecimal.ZERO, result.unrealizedPnlPct)
    }

    // ── allocation ────────────────────────────────────────────

    @Test
    fun `자산 1개일 때 HHI는 최대값 1`() {
        val asset = stock(currentValue = bd("1000000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset))

        val result = svc().allocation(userId)

        assertEquals(0, bd("1.0000").compareTo(result.concentrationHHI))
    }

    @Test
    fun `동일 가치 2개 자산 - HHI는 절반`() {
        val s1 = stock(currentValue = bd("500000"))
        val s2 = crypto(currentValue = bd("500000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(s1, s2))

        val result = svc().allocation(userId)

        assertEquals(0, bd("0.5000").compareTo(result.concentrationHHI))
    }

    @Test
    fun `자산 없으면 allocation top5Concentration은 0`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().allocation(userId)

        assertEquals(0, BigDecimal.ZERO.compareTo(result.concentrationHHI))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.top5Concentration))
    }

    // ── positions ─────────────────────────────────────────────

    @Test
    fun `positions - currentValue 내림차순 정렬`() {
        val cheap = stock(name = "저가주", currentValue = bd("100000"))
        val mid   = stock(name = "중가주", currentValue = bd("300000"))
        val exp   = stock(name = "고가주", currentValue = bd("500000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(cheap, mid, exp))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().positions(userId)

        assertEquals("고가주", result.positions[0].name)
        assertEquals("중가주", result.positions[1].name)
        assertEquals("저가주", result.positions[2].name)
    }

    @Test
    fun `positions - 수익률 계산`() {
        val asset = stock(purchasePrice = bd("50000"), quantity = bd("10"), currentValue = bd("600000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().positions(userId)

        val row = result.positions[0]
        assertEquals(0, bd("100000").compareTo(row.unrealizedPnl))
        assertEquals(0, bd("20.00").compareTo(row.unrealizedPnlPct))
    }

    @Test
    fun `positions - 매입원가 0이면 수익률 0%`() {
        val asset = stock(purchasePrice = bd("0"), quantity = bd("0"), currentValue = bd("0"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().positions(userId)

        assertEquals(BigDecimal.ZERO, result.positions[0].unrealizedPnlPct)
    }

    @Test
    fun `positions - 계좌 없으면 Unknown으로 표시`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(stock()))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().positions(userId)

        assertEquals("Unknown", result.positions[0].accountName)
    }

    @Test
    fun `positions - 총 값 합산`() {
        val a1 = stock(purchasePrice = bd("50000"), quantity = bd("10"), currentValue = bd("600000"))
        val a2 = stock(purchasePrice = bd("20000"), quantity = bd("5"),  currentValue = bd("110000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(a1, a2))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().positions(userId)

        assertEquals(0, bd("710000").compareTo(result.totalCurrentValue))
        assertEquals(0, bd("600000").compareTo(result.totalPurchaseCost))
        assertEquals(0, bd("110000").compareTo(result.totalUnrealizedPnl))
    }

    // ── performance (빈 DB) ───────────────────────────────────
    // Mockito는 List 반환 메서드에 기본으로 emptyList()를 반환하므로 jdbc stub 불필요

    @Test
    fun `performance - 이력 없으면 totalReturn은 현재 손익 기반`() {
        val asset = stock(purchasePrice = bd("50000"), quantity = bd("10"), currentValue = bd("600000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset))

        val result = svc().performance(userId, "1M")

        assertEquals(0, bd("20.00").compareTo(result.totalReturn))
        assertTrue(result.dailySeries.isEmpty())
    }

    // ── risk (빈 DB) ──────────────────────────────────────────

    @Test
    fun `risk - 이력 없으면 모든 지표 null`() {
        val result = svc().risk(userId)

        assertNull(result.volatility)
        assertNull(result.var95)
        assertNull(result.maxDrawdown)
        assertNull(result.sharpeRatio)
        assertNull(result.calmarRatio)
        assertTrue(result.series.isEmpty())
    }

    // ── byCurrency breakdown ──────────────────────────────────

    @Test
    fun `통화 그룹핑 - KRW와 USD 분리`() {
        val krwAsset = stock(currentValue = bd("300000"), currency = "KRW")
        val usdAsset = usdStock(currentValue = bd("200"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(krwAsset, usdAsset))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().summary(userId)

        val currencies = result.byCurrency.map { it.currency }
        assertTrue(currencies.contains("KRW"))
        assertTrue(currencies.contains("USD"))
    }

    @Test
    fun `summary NAV는 통화별로 KRW 환산 후 합산한다`() {
        // 1,000,000 KRW 주식 + 1,000 USD 자산(환율 1,300)
        val krw = stock(currentValue = bd("1000000"), currency = "KRW")
        val usd = usdStock(currentValue = bd("1000"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(krw, usd))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val fx = object : FxConverter {
            override fun toKrw(amount: BigDecimal, currency: String) =
                if (currency.uppercase() == "KRW") amount else amount.multiply(bd("1300"))
        }

        val result = svc(fx).summary(userId)

        // 1,000,000 + 1,000 * 1,300 = 2,300,000 (raw 합산이면 1,001,000)
        assertEquals(0, bd("2300000").compareTo(result.nav))
        // byCurrency USD 버킷도 KRW 환산값(1,300,000)으로 표기
        val usdBucket = result.byCurrency.first { it.currency == "USD" }
        assertEquals(0, bd("1300000").compareTo(usdBucket.value))
    }

    // ── helper factories ──────────────────────────────────────

    private fun stock(
        name: String = "테스트 주식",
        purchasePrice: BigDecimal = bd("1000"),
        quantity: BigDecimal = bd("10"),
        currentValue: BigDecimal = bd("10000"),
        currency: String = "KRW",
    ) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK,
        sourceType = AssetSourceType.MANUAL, name = name,
        symbol = "TEST", quantity = quantity, purchasePrice = purchasePrice,
        currentValue = currentValue, currency = currency,
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )

    private fun crypto(currentValue: BigDecimal = bd("100000")) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.CRYPTO,
        sourceType = AssetSourceType.MANUAL, name = "비트코인",
        symbol = "BTC", quantity = bd("0.1"), purchasePrice = bd("800000"),
        currentValue = currentValue, currency = "USD",
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )

    private fun usdStock(currentValue: BigDecimal = bd("200")) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK,
        sourceType = AssetSourceType.MANUAL, name = "Apple",
        symbol = "AAPL", quantity = bd("1"), purchasePrice = bd("150"),
        currentValue = currentValue, currency = "USD",
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )

    private fun account() = Account.create(
        userId = userId,
        provider = AccountProvider.MANUAL,
        accountType = AccountType.MANUAL,
        accountName = "내 계좌",
    )

    // ── benchmark (QA P1 #10) ─────────────────────────────────

    private fun benchStore(
        vararg data: Pair<com.allfolio.unifiedasset.domain.benchmark.BenchmarkType, List<Pair<java.time.LocalDate, BigDecimal>>>,
    ) = object : com.allfolio.unifiedasset.application.port.BenchmarkDailyStore {
        private val map = data.toMap()
        override fun latestDate(type: com.allfolio.unifiedasset.domain.benchmark.BenchmarkType) = null
        override fun upsert(
            type: com.allfolio.unifiedasset.domain.benchmark.BenchmarkType,
            rows: List<Pair<java.time.LocalDate, BigDecimal>>,
        ) = Unit
        override fun series(
            type: com.allfolio.unifiedasset.domain.benchmark.BenchmarkType,
            from: java.time.LocalDate, to: java.time.LocalDate,
        ) = map[type].orEmpty().filter { it.first in from..to }
    }

    @Test
    fun `벤치마크는 실제 지수 시계열로 기간 수익률을 계산한다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<DailyPerf>>(), any(), any()))
            .thenReturn(emptyList())
        val today = java.time.LocalDate.now()
        val store = benchStore(
            com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.SPX to listOf(
                today.minusDays(30) to bd("100"),
                today to bd("110"),
            ),
        )

        val result = svc(benchmarkStore = store).benchmark(userId, "1M")

        val spx = result.benchmarks.single()
        assertEquals("S&P 500", spx.name)
        assertEquals(0, bd("10.00").compareTo(spx.benchmarkReturn)) {
            "expected +10.00% but was ${spx.benchmarkReturn}"
        }
    }

    @Test
    fun `지수 데이터가 없으면 하드코딩 폴백 없이 빈 목록을 반환한다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<DailyPerf>>(), any(), any()))
            .thenReturn(emptyList())

        val result = svc().benchmark(userId, "1M")

        assertTrue(result.benchmarks.isEmpty()) { "합성 벤치마크가 남아 있음: ${result.benchmarks}" }
        assertTrue(result.series.isEmpty()) { "합성 시계열이 남아 있음 (${result.series.size} rows)" }
    }

    @Test
    fun `시계열은 포트폴리오 percent와 지수 정규화 percent를 결합한다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        val today = java.time.LocalDate.now()
        val perfRows = listOf(
            DailyPerf(today.minusDays(2), bd("1000000"), bd("0"), bd("0"), null, null),
            DailyPerf(today, bd("1050000"), bd("0"), bd("0.05"), null, null),
        )
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<DailyPerf>>(), any(), any()))
            .thenReturn(perfRows)
        val store = benchStore(
            com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.KOSPI to listOf(
                today.minusDays(30) to bd("2500"),
                today to bd("2600"),
            ),
        )

        val result = svc(benchmarkStore = store).benchmark(userId, "1M")

        val last = result.series.last()
        // 포트폴리오 cumulative_return(ratio 0.05) → percent 5.00
        assertEquals(0, bd("5.00").compareTo(last.portfolio))
        // KOSPI 2500 → 2600 = +4.00%
        assertEquals(0, bd("4.00").compareTo(last.kospi))
        // 데이터 없는 지수는 null (합성값 금지)
        assertNull(last.sp500)
        assertNull(last.btc)
    }

    private fun bd(s: String) = BigDecimal(s)

    // Mockito any() 헬퍼 (Kotlin null safety 우회)
    private fun <T> any(): T = org.mockito.Mockito.any()
}
