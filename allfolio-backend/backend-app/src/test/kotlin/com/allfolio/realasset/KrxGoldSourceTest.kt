package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
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

class KrxGoldSourceTest {

    private val asOf = LocalDate.of(2026, 8, 18)

    @Test
    fun `시세와 중량을 곱해 평가액을 낸다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "198350.0000", "KRW/g"))

        val valuation = KrxGoldSource(lookup).valuate(gold(quantity = "10", unit = "g"), asOf)

        assertThat(valuation).isNotNull
        assertThat(valuation!!.valuationKrw).isEqualByComparingTo("1983500")
        assertThat(valuation.unitPrice).isEqualByComparingTo("198350.0000")
        assertThat(valuation.priceAsOf).isEqualTo(LocalDate.of(2026, 8, 14))
        assertThat(valuation.priceBasis).isEqualTo(PriceBasis.TRADE)
        assertThat(valuation.confidence).isEqualTo(ConfidenceLevel.HIGH)
    }

    /**
     * **단위를 시세에 맞춰 환산한다.** 1돈 = 3.75g이므로 198,350 x 3.75 = 743,812.5 → 743,813.
     * 환산을 빠뜨리면 1돈짜리 금이 1g으로 평가돼 **평가액이 1/3.75로 줄어든다.**
     */
    @Test
    fun `돈 단위를 그램으로 환산해 곱한다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "198350.0000", "KRW/g"))

        val valuation = KrxGoldSource(lookup).valuate(gold(quantity = "1", unit = "돈"), asOf)

        assertThat(valuation!!.valuationKrw).isEqualByComparingTo("743813")
    }

    /**
     * **모르는 단위는 계산하지 않는다.** `symbol`이 자유 문자열이라 무엇이든 들어올 수 있는데,
     * 기본값 g을 주면 돈으로 입력한 금이 3.75배로 평가된다 — 숫자가 그럴듯해 아무도 못 잡는다.
     */
    @Test
    fun `단위를 해석할 수 없으면 계산하지 않는다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "198350.0000", "KRW/g"))

        assertThat(KrxGoldSource(lookup).valuate(gold(quantity = "10", unit = "kg"), asOf)).isNull()
        assertThat(KrxGoldSource(lookup).valuate(gold(quantity = "10", unit = null), asOf)).isNull()
    }

    /**
     * AF-108이 단위를 코드 상수가 아니라 행에 저장하는 이유가 "소스가 단위를 바꾼 날 저장은
     * 멀쩡한데 화면만 조용히 틀린다"를 막는 것이다. 어댑터가 KRW/g를 가정해 버리면
     * 그 방어가 여기서 무효가 된다.
     */
    @Test
    fun `시세 단위가 원per그램이 아니면 계산하지 않는다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "198350.0000", "KRW/돈"))

        assertThat(KrxGoldSource(lookup).valuate(gold(quantity = "10", unit = "g"), asOf)).isNull()
    }

    /** 수집 시작 이전이거나 시세가 아직 없는 구간. 0원으로 메우면 순자산이 통째로 꺼진다 */
    @Test
    fun `시세가 없으면 null을 준다`() {
        assertThat(KrxGoldSource(FakeLookup()).valuate(gold(quantity = "10", unit = "g"), asOf)).isNull()
    }

    @Test
    fun `금만 맡는다`() {
        val source = KrxGoldSource(FakeLookup())

        assertThat(source.supports(AssetType.GOLD)).isTrue()
        assertThat(source.supports(AssetType.STOCK)).isFalse()
        assertThat(source.supports(AssetType.REAL_ESTATE)).isFalse()
        assertThat(source.supports(AssetType.CRYPTO)).isFalse()
    }

    // ── 테스트 픽스처 ───────────────────────────────────────────────────────

    private fun gold(quantity: String, unit: String?) = Asset.reconstruct(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        category = AssetCategory.MANUAL,
        type = AssetType.GOLD,
        sourceType = AssetSourceType.MANUAL,
        name = "금",
        symbol = unit,
        quantity = BigDecimal(quantity),
        purchasePrice = BigDecimal("190000"),
        currentValue = BigDecimal("1900000"),
        currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
        confidenceLevel = ConfidenceLevel.LOW,
        lastUpdatedAt = LocalDateTime.of(2026, 8, 1, 9, 0),
        createdAt = LocalDateTime.of(2026, 8, 1, 9, 0),
        memo = null,
    )

    private fun quote(tradeDate: String, price: String, unit: String) =
        CommodityQuote(LocalDate.parse(tradeDate), BigDecimal(price), unit)

    private class FakeLookup(vararg quotes: Pair<String, CommodityQuote>) : CommodityQuoteLookup {
        private val byCode = quotes.toMap()

        override fun latestAsOf(code: String, asOf: LocalDate): CommodityQuote? =
            byCode[code]?.takeIf { !it.tradeDate.isAfter(asOf) }
    }
}
