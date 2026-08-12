package com.allfolio.dashboard

import com.allfolio.fx.CurrencyConverter
import com.allfolio.fx.FxRateService
import com.allfolio.fx.UsdQuoteRef
import com.allfolio.snapshot.infrastructure.repository.BenchmarkDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.RiskDailyJpaRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

// QA P1 #9/#11 — 대시보드 순자산은 KRW 환산 후 합산(navInKrw 규약), KRW 집계는 scale 0.
class GetDashboardUseCaseFxTest {

    private val userId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    private val assetRepository = mock(AssetRepository::class.java)
    private val performanceRepo = mock(PerformanceDailyJpaRepository::class.java)
    private val riskRepo = mock(RiskDailyJpaRepository::class.java)
    private val benchmarkRepo = mock(BenchmarkDailyJpaRepository::class.java)
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1400")
    }

    // AF-105 — 출처 표기가 쓰는 환율. 위 fx 스텁과 **일부러 같은 1400**이다.
    // 운영에서도 둘은 같은 값일 수밖에 없다(UnifiedAssetFxConverterAdapter.toKrw가
    // CurrencyConverter.toKrw에 위임하고, 그쪽이 sourceOf의 rate를 그대로 쓴다).
    // 픽스처에서 둘을 다르게 두면 "밝히는 환율 = 쓰는 환율" 불변식을 검증할 수 없게 된다.
    private val fxRateService = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("1400")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
        override fun usdQuoteRef() = UsdQuoteRef(BigDecimal("1400"), LocalDate.of(2026, 8, 11), 32)
    }

    private val useCase = GetDashboardUseCase(
        assetRepository, performanceRepo, riskRepo, benchmarkRepo, fx,
        mock(com.allfolio.unifiedasset.application.port.CashFlowRepository::class.java),
        CurrencyConverter(fxRateService),
    )

    private fun asset(name: String, currentValue: String, currency: String) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK, sourceType = AssetSourceType.MANUAL,
        name = name, symbol = name, quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal.TEN, currentValue = BigDecimal(currentValue),
        currency = currency, valuationMethod = ValuationMethod.USER_INPUT,
    )

    @Test
    fun `30일 전 스냅샷이 없으면 change30d는 null (비교 데이터 없음)`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(asset("삼성전자", "1000000", "KRW")))
        // perf30d 조회는 mock 기본값 null → 비교 기준 없음
        val res = useCase.execute(userId)
        assertThat(res.netWorth.change30d).isNull()
        assertThat(res.netWorth.changeRate30d).isNull()
    }

    @Test
    fun `순자산은 USD 자산을 KRW로 환산해 합산한다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(
            asset("삼성전자", "1000000", "KRW"),
            asset("AAPL", "1000", "USD"),   // × 1400 = 1,400,000
        ))

        val res = useCase.execute(userId)

        assertThat(res.netWorth.total).isEqualByComparingTo("2400000")   // raw sum이면 1,001,000
        assertThat(res.netWorth.liquid).isEqualByComparingTo("2400000")
    }

    @Test
    fun `KRW 집계는 소수점 없이 반올림된다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(
            asset("코인", "1000000.969", "KRW"),
        ))

        val res = useCase.execute(userId)

        assertThat(res.netWorth.total).isEqualByComparingTo("1000001")
        assertThat(res.netWorth.total.scale()).isLessThanOrEqualTo(0)
    }

    @Test
    fun `포지션 비중은 KRW 환산 가치 기준이다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(
            asset("삼성전자", "1400000", "KRW"),
            asset("AAPL", "1000", "USD"),   // 환산 1,400,000 → 비중 50%
        ))

        val res = useCase.execute(userId)

        val apple = res.portfolio.positions.first { it.name == "AAPL" }
        assertThat(apple.weight).isEqualByComparingTo("0.5")   // raw sum이면 1000/1401000≈0.0007
        val allocation = res.portfolio.allocation.single { it.type == "STOCK" }
        assertThat(allocation.value).isEqualByComparingTo("2800000")
    }

    // ── AF-105 환율 출처 표기 ─────────────────────────────────────

    @Test
    fun `원화 자산만 있으면 환율 출처가 비어 있다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(
            asset("삼성전자", "1000000", "KRW"),
        ))

        val result = useCase.execute(userId)

        assertThat(result.fxSources).isEmpty()
    }

    @Test
    fun `보유한 통화만 사전순으로 실린다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(
            asset("USDT지갑", "1000", "USDT"),
            asset("AAPL", "1000", "USD"),
        ))

        val result = useCase.execute(userId)

        assertThat(result.fxSources.map { it.currency }).containsExactly("USD", "USDT")
    }

    // 화면이 밝히는 환율이 그 순자산을 만든 환율과 달라지면, 신뢰를 만들려던 표기가 반대로 동작한다.
    @Test
    fun `밝히는 환율은 그 자산을 환산한 환율과 같다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(
            asset("AAPL", "1234.567", "USD"),
        ))

        val result = useCase.execute(userId)

        val source   = result.fxSources.single()
        val position = result.portfolio.positions.single()
        // 1234.567 × 1400 = 1,728,393.8 → HALF_UP → 1,728,394
        assertThat(position.currentValueKrw).isEqualByComparingTo("1728394")
        assertThat(position.currentValueKrw).isEqualByComparingTo(
            (position.currentValue * source.rate).setScale(0, RoundingMode.HALF_UP),
        )
    }
}
