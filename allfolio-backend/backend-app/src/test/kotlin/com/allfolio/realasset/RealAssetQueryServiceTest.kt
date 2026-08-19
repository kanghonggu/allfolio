package com.allfolio.realasset

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class RealAssetQueryServiceTest {

    private val user = UUID.randomUUID()

    @Test
    fun `평가액과 손익을 함께 준다`() {
        val asset = holding(acquiredCostKrw = 1_500_000L)
        val store = FakeStore(
            assets = listOf(asset),
            latest = mapOf(asset.id to snapshot(krw = 1_983_500L, priceAsOf = "2026-08-14", staleness = 4)),
        )

        val view = RealAssetQueryService(store).findByUser(user).single()

        assertThat(view.valuationKrw).isEqualTo(1_983_500L)
        assertThat(view.profitKrw).isEqualTo(483_500L)
        assertThat(view.priceAsOf).isEqualTo(LocalDate.of(2026, 8, 14))
        assertThat(view.stalenessDays).isEqualTo(4)
    }

    /** 손실도 그대로 낸다 — 음수를 0으로 바닥 치면 사용자가 손실을 못 본다 */
    @Test
    fun `손실이면 손익이 음수다`() {
        val asset = holding(acquiredCostKrw = 2_500_000L)
        val store = FakeStore(
            assets = listOf(asset),
            latest = mapOf(asset.id to snapshot(krw = 1_983_500L, priceAsOf = "2026-08-14", staleness = 4)),
        )

        assertThat(RealAssetQueryService(store).findByUser(user).single().profitKrw).isEqualTo(-516_500L)
    }

    /**
     * **스냅샷이 없으면 0이 아니라 null이다.** 배치가 아직 안 돌았거나(등록 당일) 산출 불가로
     * 건너뛴 자산이 여기 온다. 0을 내면 화면이 "평가액 0원 · 전액 손실"로 표시하는데,
     * 그건 사실이 아니라 **아직 모른다**는 뜻이다.
     * (AF-104에서 0을 대시로 표시하다 자릿수가 날아간 전례가 이 구분 위에 있다.)
     */
    @Test
    fun `스냅샷이 없으면 평가액과 손익이 null이다`() {
        val asset = holding(acquiredCostKrw = 1_500_000L)
        val store = FakeStore(assets = listOf(asset), latest = emptyMap())

        val view = RealAssetQueryService(store).findByUser(user).single()

        assertThat(view.valuationKrw).isNull()
        assertThat(view.profitKrw).isNull()
        assertThat(view.profitRate).isNull()
        assertThat(view.priceAsOf).isNull()
        assertThat(view.stalenessDays).isNull()
        // 취득 정보는 스냅샷과 무관하게 늘 있다
        assertThat(view.acquiredCostKrw).isEqualTo(1_500_000L)
    }

    /** 수익률은 취득가 대비다. 소수 넷째 자리까지 — 화면이 % 둘째 자리를 쓴다 */
    @Test
    fun `수익률은 취득가 대비로 낸다`() {
        val asset = holding(acquiredCostKrw = 1_000_000L)
        val store = FakeStore(
            assets = listOf(asset),
            latest = mapOf(asset.id to snapshot(krw = 1_250_000L, priceAsOf = "2026-08-14", staleness = 4)),
        )

        assertThat(RealAssetQueryService(store).findByUser(user).single().profitRate)
            .isEqualByComparingTo("0.2500")
    }

    /**
     * **취득가 0은 0으로 나누는 자리다.** 증여받은 금을 0원으로 등록하면 여기가 터진다 —
     * 예외로 끝내면 그 자산 하나 때문에 목록 전체가 500이 된다. 수익률만 null로 둔다.
     */
    @Test
    fun `취득가가 0이면 수익률은 null이고 평가액은 그대로다`() {
        val asset = holding(acquiredCostKrw = 0L)
        val store = FakeStore(
            assets = listOf(asset),
            latest = mapOf(asset.id to snapshot(krw = 1_983_500L, priceAsOf = "2026-08-14", staleness = 4)),
        )

        val view = RealAssetQueryService(store).findByUser(user).single()

        assertThat(view.profitRate).isNull()
        assertThat(view.valuationKrw).isEqualTo(1_983_500L)
        assertThat(view.profitKrw).isEqualTo(1_983_500L)
    }

    // ── 테스트 픽스처 ───────────────────────────────────────────────────────

    private fun holding(acquiredCostKrw: Long) = RealAssetHolding(
        id = UUID.randomUUID(),
        assetType = AssetType.GOLD,
        subType = "KRX_ACCOUNT",
        name = "금 10g",
        quantity = BigDecimal("10.0000"),
        purity = BigDecimal("1.0000"),
        acquiredAt = LocalDate.of(2026, 8, 1),
        acquiredCostKrw = acquiredCostKrw,
    )

    private fun snapshot(krw: Long, priceAsOf: String, staleness: Int) = LatestValuation(
        unitPrice = BigDecimal("198350.0000"),
        priceUnit = "KRW/g",
        valuationKrw = krw,
        valuedOn = LocalDate.of(2026, 8, 18),
        priceAsOf = LocalDate.parse(priceAsOf),
        stalenessDays = staleness,
        priceBasis = PriceBasis.TRADE,
        confidence = Confidence.HIGH,
    )

    private class FakeStore(
        private val assets: List<RealAssetHolding>,
        private val latest: Map<UUID, LatestValuation>,
    ) : RealAssetQueryService.Store {
        override fun holdings(userId: UUID) = assets

        override fun latestValuations(assetIds: Collection<UUID>) =
            latest.filterKeys { it in assetIds }
    }
}
