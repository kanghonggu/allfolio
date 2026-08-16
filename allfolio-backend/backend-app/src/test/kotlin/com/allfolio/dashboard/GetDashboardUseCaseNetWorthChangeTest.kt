package com.allfolio.dashboard

import com.allfolio.fx.CurrencyConverter
import com.allfolio.fx.FxRateService
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import com.allfolio.snapshot.infrastructure.repository.BenchmarkDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.RiskDailyJpaRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * AF-95 — 순자산 "30일 전 대비"가 입출금을 차감하지 않아 편입액이 수익으로 잡히던 문제.
 *
 * 라이브에서 본계정이 +2060.43%로 표시됐다. 30일 전 NAV가 작을수록 비율이 폭발한다.
 * 기간 수익률(TWR)은 이미 flow-aware로 고쳐져 있었고, 순자산 변화율만 예전 방식이 남아 있었다.
 */
class GetDashboardUseCaseNetWorthChangeTest {

    private val userId: UUID = UUID.randomUUID()
    private val today: LocalDate = LocalDate.now()

    @Test
    fun `기저일 이후 입금은 손익에서 차감한다`() {
        // 30일 전 100만 → 오늘 3,850만. 다만 그 사이 3,700만이 입금됐다.
        // 실제 투자손익은 50만이지 3,750만이 아니다.
        val netWorth = execute(
            nowValue = "38500000",
            baseline = perf(today.minusDays(30), "1000000"),
            flows = listOf(deposit(today.minusDays(5), "37000000")),
        )

        assertThat(netWorth.change30d).isEqualByComparingTo("500000")
        assertThat(netWorth.changeRate30d).isEqualByComparingTo("50.00")
        assertThat(netWorth.netFlow30d).isEqualByComparingTo("37000000")
    }

    @Test
    fun `입출금이 없으면 예전과 같은 NAV 차분이다`() {
        val netWorth = execute(
            nowValue = "1200000",
            baseline = perf(today.minusDays(30), "1000000"),
            flows = emptyList(),
        )

        assertThat(netWorth.change30d).isEqualByComparingTo("200000")
        assertThat(netWorth.changeRate30d).isEqualByComparingTo("20.00")
        assertThat(netWorth.netFlow30d).isEqualByComparingTo("0")
    }

    @Test
    fun `출금은 손익에 도로 더한다`() {
        // 100만에서 30만을 빼갔는데 잔고가 90만이면, 투자로는 20만을 번 것이다
        val netWorth = execute(
            nowValue = "900000",
            baseline = perf(today.minusDays(30), "1000000"),
            flows = listOf(withdrawal(today.minusDays(3), "300000")),
        )

        assertThat(netWorth.change30d).isEqualByComparingTo("200000")
        assertThat(netWorth.netFlow30d).isEqualByComparingTo("-300000")
    }

    @Test
    fun `기저 관측일 이전 입금은 이미 기저 NAV에 반영돼 있어 차감하지 않는다`() {
        // 기저 스냅샷(day-30)보다 앞선 day-40 입금은 그 NAV 안에 이미 들어 있다
        val netWorth = execute(
            nowValue = "1200000",
            baseline = perf(today.minusDays(30), "1000000"),
            flows = listOf(deposit(today.minusDays(40), "900000")),
        )

        assertThat(netWorth.change30d).isEqualByComparingTo("200000")
        assertThat(netWorth.netFlow30d).isEqualByComparingTo("0")
    }

    @Test
    fun `내부 이체는 외부 유입이 아니므로 차감하지 않는다`() {
        val netWorth = execute(
            nowValue = "1200000",
            baseline = perf(today.minusDays(30), "1000000"),
            flows = listOf(
                CashFlow.create(
                    userId = userId, accountId = null, flowDate = today.minusDays(2),
                    type = FlowType.TRANSFER_IN, amount = BigDecimal("500000"), currency = "KRW",
                    amountKrw = BigDecimal("500000"), memo = null,
                ),
            ),
        )

        assertThat(netWorth.change30d).isEqualByComparingTo("200000")
        assertThat(netWorth.netFlow30d).isEqualByComparingTo("0")
    }

    @Test
    fun `기저 스냅샷이 없으면 비교 데이터 없음으로 남긴다`() {
        val netWorth = execute(nowValue = "1200000", baseline = null, flows = emptyList())

        assertThat(netWorth.change30d).isNull()
        assertThat(netWorth.changeRate30d).isNull()
        assertThat(netWorth.netFlow30d).isNull()
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun execute(
        nowValue: String,
        baseline: PerformanceDailyEntity?,
        flows: List<CashFlow>,
    ): NetWorthDto {
        val assetRepository = mock(AssetRepository::class.java)
        val performanceRepo = mock(PerformanceDailyJpaRepository::class.java)
        val riskRepo = mock(RiskDailyJpaRepository::class.java)
        val benchmarkRepo = mock(BenchmarkDailyJpaRepository::class.java)

        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset(nowValue)))
        `when`(
            performanceRepo.findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(
                anyArg(), anyArg(),
            )
        ).thenReturn(baseline)
        `when`(
            performanceRepo.findByIdPortfolioIdAndIdDateBetween(anyArg(), anyArg(), anyArg())
        ).thenReturn(listOfNotNull(baseline))

        val useCase = GetDashboardUseCase(
            assetRepository, performanceRepo, riskRepo, benchmarkRepo,
            object : FxConverter {
                override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount
                override fun rateOf(currency: String): BigDecimal = BigDecimal.ONE
            },
            FixedCashFlows(flows),
            // 이 테스트의 자산은 전부 KRW라 출처가 실리지 않는다. 환율을 1로 두는 것은
            // 위 FxConverter 스텁(항등 환산)과 같은 값이라는 뜻 — 픽스처가 서로 어긋나지 않게 한다.
            CurrencyConverter(IdentityFxRates),
        )
        return useCase.execute(userId).netWorth
    }

    private fun asset(value: String): Asset = Asset.create(
        userId = userId, accountId = UUID.randomUUID(),
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK, sourceType = AssetSourceType.STOCK_API,
        name = "삼성전자", symbol = "005930", quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal(value), currentValue = BigDecimal(value),
        currency = "KRW", valuationMethod = ValuationMethod.USER_INPUT,
    )

    private fun perf(date: LocalDate, nav: String) = PerformanceDailyEntity(
        id = SnapshotDailyId(userId, userId, date),
        nav = BigDecimal(nav),
        dailyReturn = BigDecimal.ZERO,
        cumulativeReturn = BigDecimal.ZERO,
        benchmarkReturn = null,
        alpha = null,
    )

    private fun deposit(date: LocalDate, amountKrw: String) = CashFlow.create(
        userId = userId, accountId = null, flowDate = date, type = FlowType.DEPOSIT,
        amount = BigDecimal(amountKrw), currency = "KRW", amountKrw = BigDecimal(amountKrw), memo = null,
    )

    private fun withdrawal(date: LocalDate, amountKrw: String) = CashFlow.create(
        userId = userId, accountId = null, flowDate = date, type = FlowType.WITHDRAWAL,
        amount = BigDecimal(amountKrw), currency = "KRW", amountKrw = BigDecimal(amountKrw), memo = null,
    )

    /** Kotlin non-null 파라미터에 Mockito any()를 쓰기 위한 캐스팅 헬퍼. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    /** 항등 환산 — FxConverter 스텁과 같은 환율(1)을 본다. */
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
}
