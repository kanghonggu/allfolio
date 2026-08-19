package com.allfolio.realasset

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

/**
 * `CommodityCollectServiceTest`를 템플릿으로 삼았다 — 자산별 실패 격리·요약 축이 같다.
 * 평가에만 있는 것은 **산출 불가(null)와 실패(예외)의 구분**이다. 둘을 합치면
 * "연휴라 시세가 없다"와 "코드가 터졌다"가 같은 칸에 들어가 요약이 쓸모없어진다.
 */
class RealAssetValuationServiceTest {

    private val valuedOn = LocalDate.of(2026, 8, 18)
    // 19:30 KST = 10:30 UTC. 배치가 도는 순간이다
    private val now = Instant.parse("2026-08-18T10:30:00Z")

    @Test
    fun `자산마다 스냅샷을 쓰고 건수를 보고한다`() {
        val asset = goldAsset(quantity = "10")
        val store = FakeStore(assets = listOf(asset))
        val source = FakeSource(AssetType.GOLD, valuation(krw = 1_983_500L, priceAsOf = "2026-08-14"))

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(summary.requested).isEqualTo(1)
        assertThat(summary.valued).isEqualTo(1)
        assertThat(summary.inserted).isEqualTo(1)
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()

        val saved = store.saved.single()
        assertThat(saved.realAssetId).isEqualTo(asset.id)
        assertThat(saved.valuedOn).isEqualTo(valuedOn)
        assertThat(saved.valuationKrw).isEqualTo(1_983_500L)
        assertThat(saved.priceAsOf).isEqualTo(LocalDate.of(2026, 8, 14))
    }

    /**
     * 산출 불가는 **행을 안 쓴다.** 0원을 쓰면 순자산 그래프에 그 자산만큼 절벽이 생기고,
     * 다음 날 값이 돌아오면 절벽이 그대로 남는다(스냅샷은 과거를 고치지 않는다).
     * 직전 유효 스냅샷을 그대로 두는 것이 설계 1절 원칙 3의 "호출부가 처리한다"이다.
     *
     * 그리고 **조용히 넘어가지 않는다** — 요약에 이름이 남아야 "연휴라 그렇다"와
     * "이 자산만 몇 주째 안 잡힌다"를 운영자가 가를 수 있다.
     */
    @Test
    fun `산출 불가면 행을 쓰지 않고 건너뛴 것으로 남긴다`() {
        val asset = goldAsset(quantity = "10")
        val store = FakeStore(assets = listOf(asset))
        val source = FakeSource(AssetType.GOLD, result = null)

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(store.saved).isEmpty()
        assertThat(summary.requested).isEqualTo(1)
        assertThat(summary.valued).isZero()
        assertThat(summary.failed).isZero()
        assertThat(summary.skipped).hasSize(1)
        assertThat(summary.skipped.single()).contains(asset.id.toString()).contains("산출 불가")
    }

    /**
     * v1은 금 어댑터 하나뿐인데 등록 API(G6)는 `AssetType` 전체를 받는다. 즉 시계를 등록한
     * 사용자가 생기는 순간 맡을 어댑터가 없는 자산이 배치를 지나간다.
     * **조용히 사라지면 안 된다** — 그 자산은 영원히 평가되지 않는데 아무 신호도 안 난다.
     */
    @Test
    fun `맡는 어댑터가 없는 자산도 건너뛴 것으로 남긴다`() {
        val watch = RealAsset(
            id = UUID.randomUUID(),
            assetType = AssetType.WATCH,
            sourceRef = "116233",
            quantity = BigDecimal.ONE,
            purity = BigDecimal("1.0"),
        )
        val store = FakeStore(assets = listOf(watch))
        val source = FakeSource(AssetType.GOLD, valuation(krw = 1L, priceAsOf = "2026-08-14"))

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(store.saved).isEmpty()
        assertThat(summary.valued).isZero()
        assertThat(summary.skipped.single()).contains(watch.id.toString()).contains("어댑터 없음")
    }

    /**
     * 자산 하나가 터져도 나머지를 저장한다. 예외로 끝내면 살아 있던 평가까지 같이 잃고,
     * 그날 스냅샷이 통째로 비어 순자산 그래프에 구멍이 난다.
     *
     * **실패는 건너뜀과 다른 칸에 넣는다.** "연휴라 시세가 없다"(skipped)와 "코드가 터졌다"(failed)를
     * 합치면 요약을 보고 어디를 봐야 할지 알 수 없다.
     */
    @Test
    fun `한 자산이 터져도 나머지는 저장한다`() {
        val boom = goldAsset(quantity = "10")
        val fine = goldAsset(quantity = "20")
        val store = FakeStore(assets = listOf(boom, fine))
        val source = FakeSource(
            AssetType.GOLD,
            valuation(krw = 3_967_000L, priceAsOf = "2026-08-14"),
            failingFor = setOf(boom.id),
            failure = IllegalStateException("시세 조회 실패"),
        )

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(store.saved.map { it.realAssetId }).containsExactly(fine.id)
        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.valued).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains(boom.id.toString()).contains("시세 조회 실패")
    }

    /** 재시도가 행을 늘리지 않는다 — 워크플로의 `--retry`가 이 계수에 기대어 멱등해진다 */
    @Test
    fun `이미 스냅샷이 있는 자산은 갱신으로 센다`() {
        val asset = goldAsset(quantity = "10")
        val store = FakeStore(assets = listOf(asset), existing = setOf(asset.id))
        val source = FakeSource(AssetType.GOLD, valuation(krw = 1_983_500L, priceAsOf = "2026-08-14"))

        val summary = service(store, source).valuate(valuedOn, now)

        assertThat(summary.valued).isEqualTo(1)
        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(1)
    }

    /**
     * 실측한 연휴 그대로다 — 08-18(화) 평가에 쓸 수 있는 가장 신선한 시세가 08-14(금)이라 4일.
     * **0이 정상이 아니다**(소스가 D+1 공표). 이 값이 늘 0으로 나온다면 폴백이 아니라
     * 평가일을 그대로 넣고 있는 것이다.
     */
    @Test
    fun `평가일과 시세 기준일의 차이를 staleness로 남긴다`() {
        val store = FakeStore(assets = listOf(goldAsset(quantity = "10")))
        val source = FakeSource(AssetType.GOLD, valuation(krw = 1_983_500L, priceAsOf = "2026-08-14"))

        service(store, source).valuate(valuedOn, now)

        assertThat(store.saved.single().stalenessDays).isEqualTo(4)
    }

    // ── 테스트 픽스처 ───────────────────────────────────────────────────────

    private fun service(store: FakeStore, vararg sources: ValuationSource) =
        RealAssetValuationService(sources.toList(), store)

    private fun goldAsset(quantity: String) = RealAsset(
        id = UUID.randomUUID(),
        assetType = AssetType.GOLD,
        sourceRef = "GOLD_KRX",
        quantity = BigDecimal(quantity),
        purity = BigDecimal("1.0"),
    )

    private fun valuation(krw: Long, priceAsOf: String) = Valuation(
        unitPrice = BigDecimal("198350.0000"),
        valuationKrw = krw,
        priceAsOf = LocalDate.parse(priceAsOf),
        priceUnit = "KRW/g",
        priceBasis = PriceBasis.TRADE,
        confidence = Confidence.HIGH,
    )

    private class FakeSource(
        private val type: AssetType,
        private val result: Valuation?,
        private val failingFor: Set<UUID> = emptySet(),
        private val failure: RuntimeException? = null,
    ) : ValuationSource {
        override fun supports(assetType: AssetType) = assetType == type

        override fun valuate(asset: RealAsset, asOf: LocalDate): Valuation? {
            if (asset.id in failingFor) throw failure ?: IllegalStateException("실패")
            return result
        }
    }

    private class FakeStore(
        private val assets: List<RealAsset> = emptyList(),
        private val existing: Set<UUID> = emptySet(),
    ) : RealAssetValuationService.Store {
        val saved = mutableListOf<ValuationSnapshot>()

        override fun activeAssets(): List<RealAsset> = assets

        override fun existingAssetIds(valuedOn: LocalDate): Set<UUID> = existing

        override fun save(snapshots: List<ValuationSnapshot>, now: Instant) {
            saved += snapshots
        }
    }
}
