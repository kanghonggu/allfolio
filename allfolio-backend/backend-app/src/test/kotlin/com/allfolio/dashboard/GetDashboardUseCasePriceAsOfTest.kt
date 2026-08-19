package com.allfolio.dashboard

import com.allfolio.fx.CurrencyConverter
import com.allfolio.fx.FxRateService
import com.allfolio.fx.UsdQuoteRef
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
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A1 · N2 — 자동 평가 자산의 **시세 기준일**을 화면까지 나르는지.
 *
 * **금은 `realAssets`가 아니라 `positions`로 나간다.** `AssetType.GOLD`가
 * `ILLIQUID_TYPES`(REAL_ESTATE·JEONSE·VEHICLE)에 없어 `LIQUID`이기 때문이다 —
 * "실물자산이니 실물·고정 자산 섹션이겠지"라고 짐작하고 그쪽만 고치면 화면에 아무것도 안 뜬다.
 * 그래서 이 파일이 `positions`를 본다.
 */
class GetDashboardUseCasePriceAsOfTest {

    private val userId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    private val assetRepository = mock(AssetRepository::class.java)

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount
        override fun rateOf(currency: String): BigDecimal = BigDecimal.ONE
    }

    private val fxRateService = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("1400")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
        override fun usdQuoteRef() = UsdQuoteRef(BigDecimal("1400"), LocalDate.of(2026, 8, 11), 32)
    }

    private val useCase = GetDashboardUseCase(
        assetRepository,
        mock(PerformanceDailyJpaRepository::class.java),
        mock(RiskDailyJpaRepository::class.java),
        mock(BenchmarkDailyJpaRepository::class.java),
        fx,
        mock(CashFlowRepository::class.java),
        CurrencyConverter(fxRateService),
    )

    @Test
    fun `자동 평가된 금은 시세 기준일을 함께 내보낸다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(
            listOf(valuedGold(priceAsOf = LocalDate.of(2026, 8, 14))),
        )

        val gold = useCase.execute(userId).portfolio.positions.single()

        assertThat(gold.priceAsOf).isEqualTo(LocalDate.of(2026, 8, 14))
    }

    /**
     * **수동 입력 자산은 null이어야 한다 — 오늘 날짜를 채우면 거짓말이 된다.**
     * 주식·코인은 브로커 동기화라 시세 기준일이라는 개념 자체가 없고, 부동산·차량은
     * 사람이 넣은 값이다. 화면은 이 null을 보고 아무것도 표시하지 않는다.
     */
    @Test
    fun `수동 입력 자산은 기준일이 null이다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(manualStock()))

        assertThat(useCase.execute(userId).portfolio.positions.single().priceAsOf).isNull()
    }

    /**
     * 평가 방식·신뢰도도 함께 내보낸다. 화면이 "자동 평가된 값"과 "사용자가 손으로 넣은 값"을
     * 가르려면 필요하다 — 기준일만으로는 `USER_INPUT`인데 우연히 값이 있는 경우를 못 가른다.
     */
    @Test
    fun `평가 방식과 신뢰도를 함께 내보낸다`() {
        `when`(assetRepository.findByUserId(userId)).thenReturn(
            listOf(valuedGold(priceAsOf = LocalDate.of(2026, 8, 14))),
        )

        val gold = useCase.execute(userId).portfolio.positions.single()

        assertThat(gold.valuationMethod).isEqualTo("MARKET_PRICE")
        assertThat(gold.confidenceLevel).isEqualTo("HIGH")
    }

    // ── 테스트 픽스처 ───────────────────────────────────────────────────────

    /** 평가 배치가 지나간 뒤의 금 — `MARKET_PRICE`/`HIGH` + 기준일 */
    private fun valuedGold(priceAsOf: LocalDate) = Asset.reconstruct(
        id = UUID.randomUUID(),
        userId = userId,
        accountId = accountId,
        category = AssetCategory.MANUAL,
        type = AssetType.GOLD,
        sourceType = AssetSourceType.MANUAL,
        name = "금 1돈",
        symbol = "돈",
        quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal("700000"),
        currentValue = BigDecimal("743813"),
        currency = "KRW",
        valuationMethod = ValuationMethod.MARKET_PRICE,
        confidenceLevel = ConfidenceLevel.HIGH,
        lastUpdatedAt = LocalDateTime.of(2026, 8, 19, 19, 30),
        createdAt = LocalDateTime.of(2026, 8, 1, 9, 0),
        memo = null,
        priceAsOf = priceAsOf,
    )

    private fun manualStock() = Asset.create(
        userId = userId,
        accountId = accountId,
        category = AssetCategory.FINANCIAL,
        type = AssetType.STOCK,
        sourceType = AssetSourceType.MANUAL,
        name = "삼성전자",
        symbol = "005930",
        quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal("70000"),
        currentValue = BigDecimal("75000"),
        currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
    )
}
