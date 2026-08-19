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

/**
 * `CommodityCollectServiceTest`를 템플릿으로 삼았다 — 자산별 실패 격리·요약 축이 같다.
 * 평가에만 있는 것은 **산출 불가(null)와 실패(예외)의 구분**, 그리고 **값이 같으면 안 쓰는 것**이다.
 */
class RealAssetValuationServiceTest {

    private val valuedOn = LocalDate.of(2026, 8, 18)
    private val now = LocalDateTime.of(2026, 8, 18, 19, 30)

    @Test
    fun `값이 바뀐 자산을 갱신하고 건수를 보고한다`() {
        val asset = gold(currentValue = "1500000")
        val store = FakeStore(listOf(asset))
        val source = FakeSource(valuation(krw = "1983500", priceAsOf = "2026-08-14"))

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(summary.requested).isEqualTo(1)
        assertThat(summary.updated).isEqualTo(1)
        assertThat(summary.unchanged).isZero()
        assertThat(summary.failed).isZero()

        val applied = store.applied.single()
        assertThat(applied.asset.id).isEqualTo(asset.id)
        assertThat(applied.valuation.valuationKrw).isEqualByComparingTo("1983500")
        assertThat(applied.valuedAt).isEqualTo(now)
    }

    /**
     * **값이 같으면 쓰지 않는다.** 시세가 D+1이라 대부분의 날은 어제와 같은 값이 나온다.
     * 그때마다 저장하면 `last_updated_at`이 매일 갱신돼 **"언제 실제로 값이 바뀌었나"를
     * 알 수 없게 된다** — 화면이 신선도를 말할 때 쓰는 바로 그 컬럼이다.
     */
    @Test
    fun `값이 같으면 저장하지 않는다`() {
        val asset = gold(currentValue = "1983500")
        val store = FakeStore(listOf(asset))
        val source = FakeSource(valuation(krw = "1983500", priceAsOf = "2026-08-14"))

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(store.applied).isEmpty()
        assertThat(summary.updated).isZero()
        assertThat(summary.unchanged).isEqualTo(1)
        assertThat(summary.failed).isZero()
    }

    /** 스케일만 다른 같은 값도 같은 값이다 — `1983500` vs `1983500.0000000000` */
    @Test
    fun `스케일이 달라도 같은 값이면 저장하지 않는다`() {
        val store = FakeStore(listOf(gold(currentValue = "1983500.0000000000")))
        val source = FakeSource(valuation(krw = "1983500", priceAsOf = "2026-08-14"))

        assertThat(service(store, source).valuate(valuedOn, now).unchanged).isEqualTo(1)
        assertThat(store.applied).isEmpty()
    }

    /**
     * 산출 불가는 **직전 값을 그대로 둔다.** 0으로 덮으면 순자산 그래프에 절벽이 생기고,
     * 다음 날 값이 돌아와도 과거는 안 고쳐진다. 조용히 넘어가지도 않는다 —
     * 요약에 이름이 남아야 "연휴라 그렇다"와 "이 자산만 몇 주째 안 잡힌다"를 가를 수 있다.
     */
    @Test
    fun `산출 불가면 덮지 않고 건너뛴 것으로 남긴다`() {
        val asset = gold(currentValue = "1500000", unit = "kg")
        val store = FakeStore(listOf(asset))
        val source = FakeSource(null)

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(store.applied).isEmpty()
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()
        // 단위가 사유에 실려야 "왜 이 자산만 빠지나"를 요약만 보고 안다
        assertThat(summary.skipped.single()).contains(asset.id.toString()).contains("kg")
    }

    /**
     * 자산 하나가 터져도 나머지를 저장한다. 예외로 끝내면 살아 있던 평가까지 잃는다.
     * **실패는 건너뜀과 다른 칸에 넣는다** — 합치면 요약을 보고 어디를 봐야 할지 알 수 없다.
     */
    @Test
    fun `한 자산이 터져도 나머지는 저장한다`() {
        val boom = gold(currentValue = "1000000")
        val fine = gold(currentValue = "1000000")
        val store = FakeStore(listOf(boom, fine))
        val source = FakeSource(
            valuation(krw = "1983500", priceAsOf = "2026-08-14"),
            failingFor = setOf(boom.id),
            failure = IllegalStateException("시세 조회 실패"),
        )

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(store.applied.map { it.asset.id }).containsExactly(fine.id)
        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.updated).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains(boom.id.toString()).contains("시세 조회 실패")
    }

    /**
     * 조회 대상(`JpaValuableAssetStore.VALUABLE_TYPES`)과 어댑터 목록이 갈라지는 날,
     * 그 자산이 조용히 사라지지 않게 한다.
     */
    @Test
    fun `맡는 어댑터가 없는 자산은 건너뛴 것으로 남긴다`() {
        val estate = gold(currentValue = "500000000", type = AssetType.REAL_ESTATE)
        val store = FakeStore(listOf(estate))

        val summary = service(store, FakeSource(valuation(krw = "1", priceAsOf = "2026-08-14")))
            .valuate(valuedOn, now)

        assertThat(store.applied).isEmpty()
        assertThat(summary.skipped.single()).contains(estate.id.toString()).contains("어댑터 없음")
    }

    /** 대상이 없어도 정상이다 — 아무도 금을 안 넣었을 수 있다 */
    @Test
    fun `대상이 없으면 빈 요약이다`() {
        val store = FakeStore(emptyList())

        val summary = service(store, FakeSource(null)).valuate(valuedOn, now)

        assertThat(summary.requested).isZero()
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()
        assertThat(store.applied).isEmpty()
    }

    // ── 테스트 픽스처 ───────────────────────────────────────────────────────

    private fun service(store: FakeStore, vararg sources: ValuationSource) =
        RealAssetValuationService(sources.toList(), store)

    private fun gold(
        currentValue: String,
        unit: String? = "g",
        type: AssetType = AssetType.GOLD,
    ) = Asset.reconstruct(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        category = AssetCategory.MANUAL,
        type = type,
        sourceType = AssetSourceType.MANUAL,
        name = "금",
        symbol = unit,
        quantity = BigDecimal("10"),
        purchasePrice = BigDecimal("150000"),
        currentValue = BigDecimal(currentValue),
        currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
        confidenceLevel = ConfidenceLevel.LOW,
        lastUpdatedAt = LocalDateTime.of(2026, 8, 1, 9, 0),
        createdAt = LocalDateTime.of(2026, 8, 1, 9, 0),
        memo = null,
    )

    private fun valuation(krw: String, priceAsOf: String) = Valuation(
        unitPrice = BigDecimal("198350.0000"),
        valuationKrw = BigDecimal(krw),
        priceAsOf = LocalDate.parse(priceAsOf),
        priceBasis = PriceBasis.TRADE,
        confidence = ConfidenceLevel.HIGH,
    )

    private class FakeSource(
        private val result: Valuation?,
        private val failingFor: Set<UUID> = emptySet(),
        private val failure: RuntimeException? = null,
    ) : ValuationSource {
        override fun supports(assetType: AssetType) = assetType == AssetType.GOLD

        override fun valuate(asset: Asset, asOf: LocalDate): Valuation? {
            if (asset.id in failingFor) throw failure ?: IllegalStateException("실패")
            return result
        }
    }

    private class FakeStore(private val assets: List<Asset>) : RealAssetValuationService.Store {
        val applied = mutableListOf<RealAssetValuationService.ValuationUpdate>()

        override fun valuableAssets(): List<Asset> = assets

        override fun apply(updates: List<RealAssetValuationService.ValuationUpdate>) {
            applied += updates
        }
    }
}
