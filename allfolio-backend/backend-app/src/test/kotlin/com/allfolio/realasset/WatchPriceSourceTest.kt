package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.allfolio.unifiedasset.infrastructure.entity.WatchValuationCacheEntity
import com.allfolio.unifiedasset.infrastructure.jpa.WatchValuationCacheJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 값은 2026-09-02 `/api/valuation` 실측이다 (ref=126300 · 중앙값 16,678,002 · 표본 71).
 */
class WatchPriceSourceTest {

    private val cache = mock(WatchValuationCacheJpaRepository::class.java)
    private val source = WatchPriceSource(cache)

    private val asOf = LocalDate.of(2026, 9, 2)

    private fun watch(symbol: String?) = Asset.create(
        userId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        category = AssetCategory.MANUAL,
        type = AssetType.WATCH,
        sourceType = AssetSourceType.MANUAL,
        name = "롤렉스 데이트저스트",
        symbol = symbol,
        quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal("15000000"),
        currentValue = BigDecimal("15000000"),
        currency = "KRW",
        valuationMethod = ValuationMethod.MARKET_PRICE,
    )

    private fun row(
        refKey: String = "126300",
        median: Long = 16_678_002,
        confidence: String = "MEDIUM",
        rowAsOf: LocalDate = asOf,
    ) = WatchValuationCacheEntity(
        id = UUID.randomUUID(),
        refKey = refKey,
        asOf = rowAsOf,
        windowDays = 30,
        sampleSize = 71,
        medianKrw = median,
        p25Krw = 15_443_948,
        p75Krw = 17_991_527,
        dispersion = BigDecimal("0.1530"),
        officialPriceKrw = 14_100_000,
        confidence = confidence,
        priceBasis = "ASK",
        collectedAt = LocalDateTime.of(2026, 9, 2, 10, 20),
    )

    @Test
    fun `시계만 맡는다`() {
        assertThat(source.supports(AssetType.WATCH)).isTrue()
        assertThat(source.supports(AssetType.GOLD)).isFalse()
        assertThat(source.supports(AssetType.REAL_ESTATE)).isFalse()
    }

    @Test
    fun `캐시의 중앙값을 그대로 평가액으로 쓴다`() {
        `when`(cache.findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc("126300", asOf))
            .thenReturn(row())

        val v = source.valuate(watch("126300"), asOf)!!

        assertThat(v.valuationKrw).isEqualByComparingTo(BigDecimal("16678002"))
        assertThat(v.unitPrice).isEqualByComparingTo(BigDecimal("16678002"))
        assertThat(v.priceAsOf).isEqualTo(asOf)
    }

    @Test
    fun `호가다 — 체결가로 내면 손익이 왜곡된다`() {
        // 🔴 chrono24 매물은 해외에 있고 카페는 개인 희망가다. TRADE로 새어 나가면
        // 금·부동산과 같은 급의 숫자로 읽힌다(설계 1절 원칙 4).
        `when`(cache.findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc("126300", asOf))
            .thenReturn(row())

        assertThat(source.valuate(watch("126300"), asOf)!!.priceBasis).isEqualTo(PriceBasis.ASK)
    }

    @Test
    fun `HIGH가 와도 MEDIUM으로 낮춘다`() {
        // 호가는 표본이 아무리 두꺼워도 "그 값에 팔린다"는 뜻이 아니다.
        // 체결가인 부동산조차 MEDIUM이 상한인데 시계가 그보다 높을 수 없다.
        `when`(cache.findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc("126300", asOf))
            .thenReturn(row(confidence = "HIGH"))

        assertThat(source.valuate(watch("126300"), asOf)!!.confidence)
            .isEqualTo(ConfidenceLevel.MEDIUM)
    }

    @Test
    fun `모르는 신뢰도는 LOW로 내린다 — 낙관 쪽으로 틀리지 않는다`() {
        `when`(cache.findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc("126300", asOf))
            .thenReturn(row(confidence = "이상한값"))

        assertThat(source.valuate(watch("126300"), asOf)!!.confidence)
            .isEqualTo(ConfidenceLevel.LOW)
    }

    @Test
    fun `직전 값으로 폴백한다 — 수집이 하루 빠져도 평가가 사라지지 않는다`() {
        // 조회는 `as_of <= asOf` 최신 한 건이다. 날짜 하한을 걸면 배치가 하루만
        // 실패해도 평가가 통째로 사라진다(금 시세와 같은 판단, 설계 3절).
        val stale = LocalDate.of(2026, 8, 28)
        `when`(cache.findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc("126300", asOf))
            .thenReturn(row(rowAsOf = stale))

        val v = source.valuate(watch("126300"), asOf)!!

        // **기준일은 평가일이 아니라 캐시 행의 날짜다.** 이걸 asOf로 덮으면 화면이
        // 없는 신선도를 주장한다.
        assertThat(v.priceAsOf).isEqualTo(stale)
    }

    @Test
    fun `ref가 없으면 산출하지 않는다`() {
        // 선택 UI(W6)를 거치지 않고 손으로 등록한 자산이다.
        assertThat(source.valuate(watch(null), asOf)).isNull()
        assertThat(source.valuate(watch("   "), asOf)).isNull()
    }

    @Test
    fun `캐시에 없으면 산출하지 않는다 — 0원으로 만들지 않는다`() {
        `when`(cache.findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc("126300", asOf))
            .thenReturn(null)

        assertThat(source.valuate(watch("126300"), asOf)).isNull()
    }

    @Test
    fun `수량을 곱하지 않는다`() {
        // 시계는 개체마다 상태·풀세트가 달라 값이 갈린다. 두 개 가진 사용자는 자산을
        // 두 건 등록한다 — 여기서 수량을 곱하면 그 전제가 조용히 깨진다.
        `when`(cache.findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc("126300", asOf))
            .thenReturn(row())

        val two = Asset.create(
            userId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            category = AssetCategory.MANUAL,
            type = AssetType.WATCH,
            sourceType = AssetSourceType.MANUAL,
            name = "롤렉스 두 개",
            symbol = "126300",
            quantity = BigDecimal("2"),
            purchasePrice = BigDecimal("30000000"),
            currentValue = BigDecimal("30000000"),
            currency = "KRW",
            valuationMethod = ValuationMethod.MARKET_PRICE,
        )

        assertThat(source.valuate(two, asOf)!!.valuationKrw)
            .isEqualByComparingTo(BigDecimal("16678002"))
    }
}
