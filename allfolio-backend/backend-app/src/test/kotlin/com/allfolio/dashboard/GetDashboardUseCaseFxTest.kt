package com.allfolio.dashboard

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

    private val useCase = GetDashboardUseCase(assetRepository, performanceRepo, riskRepo, benchmarkRepo, fx)

    private fun asset(name: String, currentValue: String, currency: String) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK, sourceType = AssetSourceType.MANUAL,
        name = name, symbol = name, quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal.TEN, currentValue = BigDecimal(currentValue),
        currency = currency, valuationMethod = ValuationMethod.USER_INPUT,
    )

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
}
