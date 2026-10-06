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
        override fun rateOf(currency: String): BigDecimal = BigDecimal.ONE
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

    private val emptyCashFlows = object : com.allfolio.unifiedasset.application.port.CashFlowRepository {
        override fun save(cashFlow: com.allfolio.unifiedasset.domain.cashflow.CashFlow) = cashFlow
        override fun findById(id: UUID): com.allfolio.unifiedasset.domain.cashflow.CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: java.time.LocalDate, to: java.time.LocalDate) =
            emptyList<com.allfolio.unifiedasset.domain.cashflow.CashFlow>()
        override fun findByUserId(userId: UUID) = emptyList<com.allfolio.unifiedasset.domain.cashflow.CashFlow>()
        override fun delete(id: UUID) = Unit
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    private fun svc(
        fx: FxConverter = identityFx,
        benchmarkStore: com.allfolio.unifiedasset.application.port.BenchmarkDailyStore = emptyBenchmarkStore,
        cashFlows: com.allfolio.unifiedasset.application.port.CashFlowRepository = emptyCashFlows,
    ) = ReportService(assetRepository, accountRepository, jdbc, fx, benchmarkStore, cashFlows)

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

    @Test
    fun `topHoldings - KRW 환산 1000원 미만 먼지 포지션은 제외한다 (QA 후속 4)`() {
        // FDUSD 18원 같은 잔여 단위가 실질 포지션처럼 노출되지 않아야 한다
        val main = stock(currentValue = bd("500000"))
        val dust = stock(name = "FDUSD 잔여", currentValue = bd("18"))
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(main, dust))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc().summary(userId)

        assertEquals(1, result.topHoldings.size)
        assertEquals("테스트 주식", result.topHoldings.first().name)
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

    @Test
    fun `risk - 입금일 NAV 급증은 변동성·VaR·MDD에 잡히지 않는다`() {
        // 운용 수익 0. 3일째 1,000만 원 입금으로 NAV가 두 배. daily_return 기반이면 그날 +100%.
        val day0 = java.time.LocalDate.of(2026, 9, 1)
        val navs = listOf("10000000", "10000000", "20000000", "20000000").mapIndexed { i, v ->
            com.allfolio.report.domain.returns.NavPoint(day0.plusDays(i.toLong()), bd(v))
        }
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<com.allfolio.report.domain.returns.NavPoint>>(), any()))
            .thenReturn(navs)
        val deposit = com.allfolio.unifiedasset.domain.cashflow.CashFlow.create(
            userId = userId, accountId = null, flowDate = day0.plusDays(2),
            type = com.allfolio.unifiedasset.domain.cashflow.FlowType.DEPOSIT,
            amount = bd("10000000"), currency = "KRW", amountKrw = bd("10000000"), memo = null,
        )
        val flows = object : com.allfolio.unifiedasset.application.port.CashFlowRepository by emptyCashFlows {
            override fun findByUserId(userId: UUID) = listOf(deposit)
        }

        val result = svc(cashFlows = flows).risk(userId)

        assertEquals(day0.plusDays(3), result.latestDate)
        assertEquals(0, BigDecimal.ZERO.compareTo(result.volatility)) { "volatility ${result.volatility}" }
        assertEquals(0, BigDecimal.ZERO.compareTo(result.var95)) { "var95 ${result.var95}" }
        assertEquals(0, BigDecimal.ZERO.compareTo(result.maxDrawdown)) { "maxDrawdown ${result.maxDrawdown}" }
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

            override fun rateOf(currency: String): BigDecimal =
                if (currency.uppercase() == "KRW") BigDecimal.ONE else bd("1300")
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

    // ── performance (QA P1 #7) ────────────────────────────────

    @Test
    fun `performance twr는 기간 카드와 같은 percent 스케일이다`() {
        // cumulative_return은 ratio(0~1) 저장 — twr 응답은 percent로 환산돼야
        // FE(fmtPct, x100 없음)에서 100배 축소 표시가 나지 않는다.
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        val row = DailyPerf(
            date = java.time.LocalDate.now(), nav = bd("38000000"),
            dailyReturn = bd("0.001"), cumulativeReturn = bd("0.2060"),
            benchmarkReturn = null, alpha = null,
        )
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<DailyPerf>>(), any(), any()))
            .thenReturn(listOf(row))

        val result = svc().performance(userId, "1M")

        assertEquals(0, bd("20.60").compareTo(result.twr)) {
            "twr expected percent 20.60 but was ${result.twr}"
        }
    }

    @Test
    fun `performance - 커버리지 미달 기간은 null이고 coverageDays를 내려준다`() {
        // 6일치 시계열 — 1W(7일)조차 못 덮으므로 전 기간 '데이터 부족'(null)이어야 한다.
        // 기존 버그: 모든 기간 버튼이 같은 값(+2060%)을 반환.
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        val today = java.time.LocalDate.now()
        val rows = (5 downTo 0).map { d ->
            DailyPerf(today.minusDays(d.toLong()), bd("1000000"), bd("0"), bd("0"), null, null)
        }
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<DailyPerf>>(), any(), any()))
            .thenReturn(rows)

        val result = svc().performance(userId, "1M")

        assertEquals(6, result.coverageDays)
        assertNull(result.periodReturns["1W"])
        assertNull(result.periodReturns["1M"])
        assertNull(result.periodReturns["1Y"])
    }

    @Test
    fun `performance - 커버리지가 되는 기간만 TWR 수치를 반환한다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        val today = java.time.LocalDate.now()
        val rows = listOf(
            DailyPerf(today.minusDays(40), bd("1000000"), bd("0"), bd("0"), null, null),
            DailyPerf(today.minusDays(1), bd("1100000"), bd("0"), bd("0"), null, null),
        )
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<DailyPerf>>(), any(), any()))
            .thenReturn(rows)

        val result = svc().performance(userId, "1M")

        // 40일 스팬 → 1W/1M은 계산 가능(+10.00%), 3M/1Y는 데이터 부족
        assertEquals(0, bd("10.00").compareTo(result.periodReturns["1M"]))
        assertNull(result.periodReturns["3M"])
        assertNull(result.periodReturns["1Y"])
    }

    @Test
    fun `positions - USD 자산은 KRW 환산값을 함께 내려준다`() {
        val fx1300 = object : FxConverter {
            override fun toKrw(amount: BigDecimal, currency: String) =
                if (currency.uppercase() == "KRW") amount else amount.multiply(bd("1300"))

            override fun rateOf(currency: String): BigDecimal =
                if (currency.uppercase() == "KRW") BigDecimal.ONE else bd("1300")
        }
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(usdStock(currentValue = bd("200"))))
        `when`(accountRepository.findByUserId(userId)).thenReturn(emptyList())

        val result = svc(fx = fx1300).positions(userId)

        val row = result.positions[0]
        assertEquals(0, bd("260000").compareTo(row.currentValueKrw)) {
            "expected 200 USD x 1300 = 260,000 but was ${row.currentValueKrw}"
        }
        assertEquals("USD", row.currency)
    }

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
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<DailyPerf>>(), any(), any()))
            .thenReturn(emptyList())

        val result = svc().benchmark(userId, "1M")

        assertTrue(result.benchmarks.isEmpty()) { "합성 벤치마크가 남아 있음: ${result.benchmarks}" }
        assertTrue(result.series.isEmpty()) { "합성 시계열이 남아 있음 (${result.series.size} rows)" }
    }

    @Test
    fun `시계열은 포트폴리오 percent와 지수 정규화 percent를 결합한다`() {
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

    // ── benchmark: 포트폴리오와 지수가 같은 창을 봐야 한다 (AF-107) ──────

    /**
     * 🔴 **알파는 두 수가 같은 기간일 때만 뜻이 있다.**
     *
     * `benchmarkReturn`은 `since = today − periodDays(period)` 기준인데, `portfolioReturn`은
     * 보유 자산의 `(평가액 − 취득가) / 취득가`였다 — **기간을 아예 안 본다.** 그래서 기간을
     * 바꾸면 지수 쪽만 움직이고 포트폴리오 숫자는 그대로였고, 그 차를 알파라고 불렀다.
     *
     * 아래 시계열은 **구간마다 수익률이 다르게** 잡혀 있다. 기간을 구별하지 않는 구현은
     * 두 단언 중 하나를 반드시 어긴다.
     *
     * | 창 | 기저 | 끝 | TWR |
     * | --- | --- | --- | --- |
     * | 1M (today−30) | 1,100,000 | 1,210,000 | **+10.00%** |
     * | 3M (today−90) | 1,000,000 | 1,210,000 | **+21.00%** |
     *
     * 보유 자산은 취득가 1,000,000 · 평가액 2,000,000(= +100%)으로 둔다. 옛 구현이면
     * 두 기간 모두 100.00이 나온다.
     */
    @Test
    fun `포트폴리오 수익률은 지수와 같은 창의 TWR이다`() {
        val today = java.time.LocalDate.now()
        // **lenient인 것이 요점이다.** 취득가 1,000,000 · 평가액 2,000,000(= +100%)을 넣어 두지만
        // 고친 구현은 자산을 읽지 않는다. strict 스텁이면 "안 불렀다"로 터지는데, 그 사실 자체가
        // 이 테스트가 지키려는 것이다 — 보유 수익률이 기간 수익률 자리에 못 들어온다.
        org.mockito.Mockito.lenient().`when`(assetRepository.findByUserId(userId)).thenReturn(
            listOf(stock(purchasePrice = bd("100000"), quantity = bd("10"), currentValue = bd("2000000"))),
        )
        stubPerformanceDaily(
            listOf(
                // 🔴 **관측일을 창 경계에 두면 안 된다.** today−30/today−90에 두었더니
                // "전 구간을 읽는다"를 창만 읽도록 바꿔도 앵커가 같아 통과했다 — 테스트가
                // 고정점 입력이라 아무것도 못 쟀다. 앵커를 창 **밖**으로 빼야 구별된다.
                DailyPerf(today.minusDays(100), bd("1000000"), bd("0"), bd("0"), null, null),
                DailyPerf(today.minusDays(35), bd("1100000"), bd("0"), bd("0.1"), null, null),
                DailyPerf(today, bd("1210000"), bd("0"), bd("0.21"), null, null),
            ),
        )

        val oneMonth = svc().benchmark(userId, "1M").portfolioReturn
        val threeMonth = svc().benchmark(userId, "3M").portfolioReturn

        assertEquals(0, bd("10.00").compareTo(oneMonth)) { "1M expected +10.00% but was $oneMonth" }
        assertEquals(0, bd("21.00").compareTo(threeMonth)) { "3M expected +21.00% but was $threeMonth" }
    }

    /**
     * 커버리지가 모자라면 **숫자를 만들어내지 않는다.** `periodTwrPercent`가 null을 주는
     * 자리이고(시계열 첫 관측이 cutoff 이후), 여기서 취득가 기준 수익률로 조용히 갈아타면
     * 화면은 그게 TWR인 줄 알고 지수와 나란히 놓는다 — 고치려던 바로 그 상태다.
     */
    @Test
    fun `시계열이 기간을 못 덮으면 포트폴리오 수익률도 알파도 null이다`() {
        val today = java.time.LocalDate.now()
        // **lenient인 것이 요점이다.** 취득가 1,000,000 · 평가액 2,000,000(= +100%)을 넣어 두지만
        // 고친 구현은 자산을 읽지 않는다. strict 스텁이면 "안 불렀다"로 터지는데, 그 사실 자체가
        // 이 테스트가 지키려는 것이다 — 보유 수익률이 기간 수익률 자리에 못 들어온다.
        org.mockito.Mockito.lenient().`when`(assetRepository.findByUserId(userId)).thenReturn(
            listOf(stock(purchasePrice = bd("100000"), quantity = bd("10"), currentValue = bd("2000000"))),
        )
        // 사흘치뿐 — 1M 창(30일)을 못 덮는다
        stubPerformanceDaily(
            listOf(
                DailyPerf(today.minusDays(2), bd("1000000"), bd("0"), bd("0"), null, null),
                DailyPerf(today, bd("1210000"), bd("0"), bd("0.21"), null, null),
            ),
        )
        val store = benchStore(
            com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.SPX to listOf(
                today.minusDays(30) to bd("100"),
                today to bd("110"),
            ),
        )

        val result = svc(benchmarkStore = store).benchmark(userId, "1M")

        assertNull(result.portfolioReturn) { "커버리지 미달인데 숫자가 나왔다: ${result.portfolioReturn}" }
        // 지수는 그대로 보여 준다 — 없는 것은 우리 쪽이다
        assertEquals(0, bd("10.00").compareTo(result.benchmarks.single().benchmarkReturn))
        assertNull(result.benchmarks.single().alpha) { "기저 없는 알파가 나왔다" }
    }

    /**
     * `performance_daily` 조회를 **`since` 인자를 실제로 지키는** 가짜로 세운다.
     *
     * 🔴 **그냥 목으로 두면 "전 구간을 읽는다"를 아무도 안 잰다.** 운영 SQL은
     * `date >= ?`로 거르는데 목은 인자와 무관하게 같은 행을 돌려주므로, 구현이
     * `"ALL"` 대신 선택 기간으로 읽도록 바뀌어도 테스트가 통과한다 — 실제로 그 변이를
     * 넣었을 때 전부 초록이었다. 앵커(기간 시작 이전 마지막 관측)를 잃는 것이 이 수정의
     * 핵심이라, 거르는 쪽을 흉내 내야 계측이 생긴다.
     */
    /**
     * 보유 시장 판정 (AF-107). *"국내주식만 가진 사용자에게 항셍은 소음이다"*.
     *
     * **숨기는 판정이 아니라 칩 기본값이다.** 통화로 가르는 것은 근사라(원화로 담은
     * 해외주식은 KOSPI로 잡힌다) 틀렸을 때 지수가 사라지면 안 된다 — 꺼진 채 남아야 한다.
     */
    @Test
    fun `보유 시장만 held가 참이다`() {
        val today = java.time.LocalDate.now()
        // 국내주식만 보유 — 암호화폐·해외주식 없음
        `when`(assetRepository.findByUserId(userId)).thenReturn(
            listOf(stock(currency = "KRW")),
        )
        stubPerformanceDaily(
            listOf(
                DailyPerf(today.minusDays(100), bd("1000000"), bd("0"), bd("0"), null, null),
                DailyPerf(today, bd("1100000"), bd("0"), bd("0.1"), null, null),
            ),
        )
        val store = benchStore(
            com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.KOSPI to
                listOf(today.minusDays(40) to bd("2500"), today to bd("2600")),
            com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.BTC to
                listOf(today.minusDays(40) to bd("100"), today to bd("120")),
        )

        val items = svc(benchmarkStore = store).benchmark(userId, "1M").benchmarks.associateBy { it.name }

        assertTrue(items.getValue("KOSPI").held) { "국내주식 보유인데 KOSPI가 held=false" }
        assertFalse(items.getValue("Bitcoin").held) { "암호화폐가 없는데 BTC가 held=true" }
        // 🔴 안 들고 있어도 **목록에는 남는다** — 판정이 틀렸을 때 영영 못 보는 것을 막는다
        assertEquals(2, items.size) { "보유 아닌 지수가 목록에서 사라졌다: ${items.keys}" }
    }

    private fun stubPerformanceDaily(rows: List<DailyPerf>) {
        `when`(jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<DailyPerf>>(), any(), any()))
            .thenAnswer { inv ->
                val since = inv.arguments.last() as java.time.LocalDate
                rows.filter { !it.date.isBefore(since) }
            }
    }

    private fun bd(s: String) = BigDecimal(s)

    // Mockito any() 헬퍼 (Kotlin null safety 우회)
    private fun <T> any(): T = org.mockito.Mockito.any()
}
