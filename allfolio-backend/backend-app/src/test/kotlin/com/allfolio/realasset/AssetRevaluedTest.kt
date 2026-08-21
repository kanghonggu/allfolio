package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetLiquidityType
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * `Asset.revalued()` — **평가가 다른 필드를 흘리지 않는지**를 지킨다.
 *
 * `Asset`은 불변이라 재평가는 새 인스턴스를 만든다. 그래서 `reconstruct`에 안 적은 필드는
 * "안 바꿈"이 아니라 **기본값으로 지워진다.** 필드가 늘 때마다 여기 한 줄이 늘어야 하고,
 * 그걸 잊으면 배치가 돌 때마다 조용히 값이 사라진다.
 *
 * 이건 가정이 아니라 실제로 있었던 일이다 — `exclusiveAreaM2`를 추가할 때 `revalued()`가
 * 그 필드를 안 넘기고 있었고, 그대로 뒀으면 **부동산을 평가할 때마다 매칭 키가 지워져**
 * 다음 평가에서 그 자산을 못 찾게 됐을 것이다.
 */
class AssetRevaluedTest {

    private val valuedAt = LocalDateTime.of(2026, 8, 21, 19, 30)

    /**
     * **매칭 키가 살아남아야 한다.** 지워지면 다음 평가에서 그 아파트의 실거래를 못 찾고,
     * 자산은 "평가 불가"로 굳는다 — 화면에는 마지막 값이 그대로 남아 있어서 안 보인다.
     */
    @Test
    fun `재평가해도 전용면적과 단지 식별자가 남는다`() {
        val asset = realEstate(exclusiveAreaM2 = "84.9700", symbol = "11110-2339")

        val revalued = asset.revalued(valuation("1250000000", "2026-08-20"), valuedAt)

        assertThat(revalued.exclusiveAreaM2).isEqualByComparingTo("84.9700")
        assertThat(revalued.symbol).isEqualTo("11110-2339")
    }

    /** 표시용 면적도 함께 살아남는다 — 매칭에는 안 쓰지만 화면이 읽는다 */
    @Test
    fun `재평가해도 표시용 면적이 남는다`() {
        val asset = realEstate(areaPyeong = "34.00")

        assertThat(asset.revalued(valuation(), valuedAt).areaPyeong).isEqualByComparingTo("34.00")
    }

    /** 대출은 순자본 계산에 쓰인다 — 흘리면 순자산이 대출금만큼 부풀어 오른다 */
    @Test
    fun `재평가해도 대출금이 남는다`() {
        val asset = realEstate(loanAmount = "400000000")

        val revalued = asset.revalued(valuation("1250000000", "2026-08-20"), valuedAt)

        assertThat(revalued.loanAmount).isEqualByComparingTo("400000000")
        assertThat(revalued.netEquity()).isEqualByComparingTo("850000000")
    }

    /**
     * 부동산은 `ILLIQUID`라 `totalPurchaseCost()`가 수량을 곱하지 않는다.
     * 이게 뒤집히면 손익이 수량 배로 틀린다.
     */
    @Test
    fun `재평가해도 유동성 구분이 남는다`() {
        val asset = realEstate()

        val revalued = asset.revalued(valuation("1250000000", "2026-08-20"), valuedAt)

        assertThat(revalued.liquidityType).isEqualTo(AssetLiquidityType.ILLIQUID)
        assertThat(revalued.totalPurchaseCost()).isEqualByComparingTo("900000000")
    }

    /**
     * **값·방법·신뢰도·기준일은 함께 움직인다.** 값만 갱신하고 나머지를 두면 화면이
     * 자동 평가된 신선한 숫자를 "사용자가 손으로 넣은 값"이라고 설명하게 된다.
     */
    @Test
    fun `평가 결과가 값과 방법과 기준일에 함께 반영된다`() {
        val asset = realEstate()

        val revalued = asset.revalued(valuation("1250000000", "2026-08-20"), valuedAt)

        assertThat(revalued.currentValue).isEqualByComparingTo("1250000000")
        assertThat(revalued.valuationMethod).isEqualTo(ValuationMethod.MARKET_PRICE)
        assertThat(revalued.confidenceLevel).isEqualTo(ConfidenceLevel.HIGH)
        assertThat(revalued.priceAsOf).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(revalued.lastUpdatedAt).isEqualTo(valuedAt)
    }

    /** 정체성은 안 바뀐다 — 새 인스턴스지만 같은 자산이다 */
    @Test
    fun `재평가해도 자산의 정체성이 유지된다`() {
        val asset = realEstate()

        val revalued = asset.revalued(valuation(), valuedAt)

        assertThat(revalued.id).isEqualTo(asset.id)
        assertThat(revalued.userId).isEqualTo(asset.userId)
        assertThat(revalued.accountId).isEqualTo(asset.accountId)
        assertThat(revalued.createdAt).isEqualTo(asset.createdAt)
        assertThat(revalued.name).isEqualTo(asset.name)
        assertThat(revalued.type).isEqualTo(AssetType.REAL_ESTATE)
        assertThat(revalued.currency).isEqualTo("KRW")
    }

    private fun valuation(krw: String = "1000000000", priceAsOf: String = "2026-08-20") =
        Valuation(
            unitPrice = BigDecimal(krw),
            valuationKrw = BigDecimal(krw),
            priceAsOf = LocalDate.parse(priceAsOf),
            priceBasis = PriceBasis.TRADE,
            confidence = ConfidenceLevel.HIGH,
        )

    private fun realEstate(
        exclusiveAreaM2: String? = "84.9700",
        areaPyeong: String? = "34.00",
        loanAmount: String? = "400000000",
        symbol: String? = "11110-2339",
    ) = Asset.reconstruct(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        category = AssetCategory.MANUAL,
        type = AssetType.REAL_ESTATE,
        sourceType = AssetSourceType.MANUAL,
        name = "종로중흥S클래스",
        symbol = symbol,
        quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal("900000000"),
        currentValue = BigDecimal("900000000"),
        currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
        confidenceLevel = ConfidenceLevel.LOW,
        lastUpdatedAt = LocalDateTime.of(2026, 8, 1, 12, 0),
        createdAt = LocalDateTime.of(2026, 7, 1, 12, 0),
        memo = null,
        subType = "OWN",
        loanAmount = loanAmount?.let(::BigDecimal),
        maturityDate = null,
        liquidityType = AssetLiquidityType.ILLIQUID,
        areaPyeong = areaPyeong?.let(::BigDecimal),
        priceAsOf = null,
        exclusiveAreaM2 = exclusiveAreaM2?.let(::BigDecimal),
    )
}
