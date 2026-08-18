package com.allfolio.realasset

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class KrxGoldSourceTest {

    private val asOf = LocalDate.of(2026, 8, 18)

    @Test
    fun `시세와 수량을 곱해 평가액을 낸다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "198350.0000", "KRW/g"))

        val valuation = KrxGoldSource(lookup).valuate(gold(quantity = "10"), asOf)

        assertThat(valuation).isNotNull
        assertThat(valuation!!.valuationKrw).isEqualTo(1_983_500L)
        assertThat(valuation.unitPrice).isEqualByComparingTo("198350.0000")
        assertThat(valuation.priceAsOf).isEqualTo(LocalDate.of(2026, 8, 14))
        assertThat(valuation.priceUnit).isEqualTo("KRW/g")
        assertThat(valuation.priceBasis).isEqualTo(PriceBasis.TRADE)
        assertThat(valuation.confidence).isEqualTo(Confidence.HIGH)
    }

    /**
     * 1돈 = 3.75g. `valuation_krw`가 BIGINT라 어딘가에서 반드시 반올림되는데,
     * 그 자리가 여기 하나여야 한다 — 저장하는 쪽마다 규칙이 생기면 같은 자산의 평가액이
     * 화면과 DB에서 갈린다. 198,350 x 3.75 = 743,812.5 → HALF_UP → 743,813.
     */
    @Test
    fun `원 단위로 반올림한다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "198350.0000", "KRW/g"))

        val valuation = KrxGoldSource(lookup).valuate(gold(quantity = "3.75"), asOf)

        assertThat(valuation!!.valuationKrw).isEqualTo(743_813L)
    }

    /** 18K는 0.75. v1은 24K 고정이지만 순도를 안 곱하면 주얼리를 받는 날 33% 과대평가된다 */
    @Test
    fun `순도를 곱한다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "200000.0000", "KRW/g"))

        val valuation = KrxGoldSource(lookup).valuate(gold(quantity = "10", purity = "0.75"), asOf)

        assertThat(valuation!!.valuationKrw).isEqualTo(1_500_000L)
    }

    /**
     * 연휴가 아무리 길어도 폴백이 닿지 않는 구간(수집 시작 이전 등)이 있다.
     * 그때 0원으로 메우면 사용자 순자산이 그 자산만큼 통째로 사라진 것처럼 보인다.
     */
    @Test
    fun `시세가 없으면 null을 준다`() {
        val valuation = KrxGoldSource(FakeLookup()).valuate(gold(quantity = "10"), asOf)

        assertThat(valuation).isNull()
    }

    /** `source_ref`는 nullable이다 — 시세 소스가 없는 자산을 나중에 받을 수 있게 열어 둔 자리 */
    @Test
    fun `시세 조인 키가 없으면 null을 준다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "198350.0000", "KRW/g"))

        val valuation = KrxGoldSource(lookup).valuate(gold(quantity = "10", sourceRef = null), asOf)

        assertThat(valuation).isNull()
    }

    /**
     * AF-108이 단위를 코드 상수가 아니라 행에 저장하는 이유가 "소스가 단위를 바꾼 날 저장은
     * 멀쩡한데 화면만 조용히 틀린다"를 막는 것이다. 어댑터가 KRW/g를 가정해 버리면 그 방어가
     * 여기서 무효가 된다 — 돈(3.75g) 단위로 바뀌어 오면 평가액이 3.75배로 뛴다.
     * 계산하지 않고 null을 준다(설계 1절 원칙 3: 산출 불가능하면 null).
     */
    @Test
    fun `단위가 원per그램이 아니면 계산하지 않는다`() {
        val lookup = FakeLookup("GOLD_KRX" to quote("2026-08-14", "198350.0000", "KRW/돈"))

        val valuation = KrxGoldSource(lookup).valuate(gold(quantity = "10"), asOf)

        assertThat(valuation).isNull()
    }

    @Test
    fun `금만 맡는다`() {
        val source = KrxGoldSource(FakeLookup())

        assertThat(source.supports(AssetType.GOLD)).isTrue()
        assertThat(source.supports(AssetType.WATCH)).isFalse()
        assertThat(source.supports(AssetType.REAL_ESTATE)).isFalse()
    }

    // ── 테스트 픽스처 ───────────────────────────────────────────────────────

    private fun gold(
        quantity: String,
        purity: String = "1.0",
        sourceRef: String? = "GOLD_KRX",
    ) = RealAsset(
        id = UUID.randomUUID(),
        assetType = AssetType.GOLD,
        sourceRef = sourceRef,
        quantity = BigDecimal(quantity),
        purity = BigDecimal(purity),
    )

    private fun quote(tradeDate: String, price: String, unit: String) =
        CommodityQuote(LocalDate.parse(tradeDate), BigDecimal(price), unit)

    private class FakeLookup(vararg quotes: Pair<String, CommodityQuote>) : CommodityQuoteLookup {
        private val byCode = quotes.toMap()

        override fun latestAsOf(code: String, asOf: LocalDate): CommodityQuote? =
            byCode[code]?.takeIf { !it.tradeDate.isAfter(asOf) }
    }
}
