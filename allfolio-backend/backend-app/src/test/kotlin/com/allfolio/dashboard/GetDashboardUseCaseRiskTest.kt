package com.allfolio.dashboard

import com.allfolio.fx.CurrencyConverter
import com.allfolio.fx.FxRateService
import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import com.allfolio.snapshot.infrastructure.repository.BenchmarkDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.RiskFreeRate
import com.allfolio.unifiedasset.application.port.RiskFreeRateSource
import com.allfolio.unifiedasset.application.usecase.RiskAdjustedRatios
import com.allfolio.unifiedasset.application.usecase.FlowAdjustedRiskSeries
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * 대시보드 MDD·변동성(샤프 분모)·VaR는 B-04 리스크 화면과 같은 플로우 조정 시계열에서 읽는다.
 * risk_daily는 daily_return(입출금 미조정)을 먹어 입금일이 수익, 출금일이 손실로 잡혔다.
 */
class GetDashboardUseCaseRiskTest {

    private val userId = UUID.randomUUID()
    private val today = LocalDate.now()

    private val assetRepository = mock(AssetRepository::class.java)
    private val performanceRepo = mock(PerformanceDailyJpaRepository::class.java)
    private val benchmarkRepo = mock(BenchmarkDailyJpaRepository::class.java)
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount
        override fun rateOf(currency: String): BigDecimal = BigDecimal.ONE
    }

    private object IdentityFxRates : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal.ONE
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal.ONE
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }

    private class FixedCashFlows(private val flows: List<CashFlow>) : CashFlowRepository {
        override fun save(cashFlow: CashFlow): CashFlow = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) = Unit
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    private fun perf(daysAgo: Long, nav: String) = PerformanceDailyEntity(
        id = SnapshotDailyId(userId, userId, today.minusDays(daysAgo)),
        nav = BigDecimal(nav),
        dailyReturn = BigDecimal.ZERO,
        cumulativeReturn = BigDecimal.ZERO,
        benchmarkReturn = null,
        alpha = null,
    )

    private fun flow(daysAgo: Long, type: FlowType, amountKrw: String) = CashFlow.create(
        userId = userId, accountId = null, flowDate = today.minusDays(daysAgo), type = type,
        amount = BigDecimal(amountKrw), currency = "KRW", amountKrw = BigDecimal(amountKrw), memo = null,
    )

    /** VaR 금액 = var95 × 유동자산이라 유동자산이 0이면 VaR가 무엇이든 0이 된다 — 1,000만 원을 둔다 */
    private fun stock(value: String): Asset = Asset.create(
        userId = userId, accountId = UUID.randomUUID(),
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK, sourceType = AssetSourceType.STOCK_API,
        name = "삼성전자", symbol = "005930", quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal(value), currentValue = BigDecimal(value),
        currency = "KRW", valuationMethod = ValuationMethod.USER_INPUT,
    )

    private fun metrics(
        series: List<PerformanceDailyEntity>,
        flows: List<CashFlow>,
        riskFree: RiskFreeRateSource = RiskFreeRateSource { null },
    ): MetricsDto {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(stock("10000000")))
        `when`(performanceRepo.findByIdPortfolioIdAndIdDateBetween(any() ?: userId, any() ?: today, any() ?: today))
            .thenReturn(series)
        return GetDashboardUseCase(
            assetRepository, performanceRepo, benchmarkRepo, fx, FixedCashFlows(flows),
            CurrencyConverter(IdentityFxRates),
            riskFree,
        ).execute(userId).portfolio.metrics
    }

    @Test
    fun `입금·출금만 있고 운용 수익이 0이면 MDD·변동성·VaR가 모두 0이다`() {
        // daily_return 기반이면 입금일 +100%, 출금일 −25% — MDD −25%, 변동성·VaR 모두 0이 아니다
        val series = listOf(
            perf(5, "10000000"),
            perf(4, "10000000"),
            perf(3, "20000000"), // 1,000만 원 입금
            perf(2, "20000000"),
            perf(1, "15000000"), // 500만 원 출금
        )
        val flows = listOf(
            flow(3, FlowType.DEPOSIT, "10000000"),
            flow(1, FlowType.WITHDRAWAL, "5000000"),
        )

        val m = metrics(series, flows)

        assertThat(m.mdd!!.value).isEqualByComparingTo("0")
        assertThat(m.volatility!!.value).isEqualByComparingTo("0")
        assertThat(m.var95!!.value).isEqualByComparingTo("0")
    }

    @Test
    fun `입출금 사이의 실제 손실 −10%만 MDD로 잡힌다`() {
        // 구간 수익률: 0, 0(입금), −10%, 0(출금) → MDD −10%.
        // daily_return이면 0, +100%, −10%, −27.8% → MDD 약 −35%.
        val series = listOf(
            perf(5, "10000000"),
            perf(4, "10000000"),
            perf(3, "20000000"), // 1,000만 원 입금
            perf(2, "18000000"), // 운용 손실 −10%
            perf(1, "13000000"), // 500만 원 출금
        )
        val flows = listOf(
            flow(3, FlowType.DEPOSIT, "10000000"),
            flow(1, FlowType.WITHDRAWAL, "5000000"),
        )

        val m = metrics(series, flows)

        assertThat(m.mdd!!.value).isEqualByComparingTo("-10.00")
    }

    @Test
    fun `대시보드 리스크 지표는 B-04 리스크 시계열의 마지막 날과 같다`() {
        val series = listOf(
            perf(9, "10000000"),
            perf(8, "10300000"),
            perf(7, "20100000"), // 1,000만 원 입금 + 운용 −1.5%
            perf(6, "19500000"),
            perf(5, "19900000"),
            perf(4, "16600000"), // 300만 원 출금 + 운용 −1.5%
            perf(3, "17100000"),
            perf(2, "16800000"),
            perf(1, "17000000"),
        )
        val flows = listOf(
            flow(7, FlowType.DEPOSIT, "10000000"),
            flow(4, FlowType.WITHDRAWAL, "3000000"),
        )

        val m = metrics(series, flows)

        val b04 = FlowAdjustedRiskSeries.build(
            series.map { NavPoint(it.id.date, it.nav) },
            flows.map { Flow(it.flowDate, it.signedKrw()) },
        ).last()
        fun pct(v: BigDecimal) = v.multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        assertThat(b04.date).isEqualTo(today.minusDays(1))
        assertThat(m.mdd!!.value).isEqualByComparingTo(pct(b04.maxDrawdown))
        assertThat(m.volatility!!.value).isEqualByComparingTo(pct(b04.annualizedVolatility))
        assertThat(m.var95!!.value)
            .isEqualByComparingTo(b04.var95.multiply(BigDecimal("10000000")).setScale(0, RoundingMode.HALF_UP))
        // 같은 숫자가 0이면 위 비교는 아무것도 못 가린다
        assertThat(m.mdd!!.value.signum()).isNegative()
        assertThat(m.var95!!.value.signum()).isNotZero()
    }

    /** 41일 이력, +1%/−0.5% 교대 운용 수익, 20일 전 1,000만 원 입금 — 구간 수익률 40건 */
    private fun fortyDays(): Pair<List<PerformanceDailyEntity>, List<CashFlow>> {
        var nav = 10_000_000.0
        val series = mutableListOf(perf(40, "10000000"))
        val flows = mutableListOf<CashFlow>()
        for (i in 1..40) {
            val daysAgo = 40L - i
            val dep = if (daysAgo == 20L) 10_000_000.0 else 0.0
            if (dep > 0) flows += flow(daysAgo, FlowType.DEPOSIT, "10000000")
            nav = (nav + dep) * (1 + if (i % 2 == 1) 0.01 else -0.005)
            series += perf(daysAgo, nav.toBigDecimal().setScale(0, RoundingMode.HALF_UP).toPlainString())
        }
        return series to flows
    }

    @Test
    fun `대시보드 샤프는 B-04와 같은 공용 계산 — 설정 이후 연환산, 무위험은 수집된 CD 91일`() {
        val (series, flows) = fortyDays()
        val cd91 = RiskFreeRate("CD_91D", today.minusDays(1), BigDecimal("3.12"))

        val m = metrics(series, flows) { cd91 }

        val b04 = RiskAdjustedRatios.compute(
            series.map { NavPoint(it.id.date, it.nav) },
            flows.map { Flow(it.flowDate, it.signedKrw()) },
            BigDecimal("3.12"),
        )!!
        assertThat(m.sharpe!!.value).isEqualByComparingTo(b04.sharpe!!.setScale(2, RoundingMode.HALF_UP))
        assertThat(m.sharpe!!.value.signum()).isNotZero()
    }

    @Test
    fun `무위험 수익률 수집값이 없으면 대시보드 샤프는 null — 상수로 메우지 않는다`() {
        val (series, flows) = fortyDays()

        assertThat(metrics(series, flows).sharpe).isNull()
    }

    @Test
    fun `구간 수익률 30건 미만이면 대시보드 샤프는 null`() {
        // 예전 대시보드는 dataDays >= 10이면 냈다
        val (series, flows) = fortyDays()
        val short = series.takeLast(30) // 관측 30건 = 구간 29건

        assertThat(metrics(short, flows.filter { it.flowDate >= short.first().id.date }) {
            RiskFreeRate("CD_91D", today, BigDecimal("3.12"))
        }.sharpe).isNull()
    }
}
