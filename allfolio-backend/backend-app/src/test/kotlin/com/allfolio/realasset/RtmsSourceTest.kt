package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetLiquidityType
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.allfolio.unifiedasset.infrastructure.entity.RtmsDealCacheEntity
import com.allfolio.unifiedasset.infrastructure.jpa.RtmsDealCacheJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 부동산 평가 어댑터.
 *
 * **이 어댑터의 설계는 "기간이 아니라 개수로 자른다"이다.** 부동산은 추세가 있어서
 * (실측 12개월 내 값 이동 p50 +7.7% · |이동| 10% 초과가 46%) 12개월치를 통째로 중앙값
 * 내면 시세가 뒤처지고, 창을 줄이면 표본이 무너진다(`n>=3` 조합이 49% → 32%).
 */
class RtmsSourceTest {

    private val repo = mock(RtmsDealCacheJpaRepository::class.java)
    private val source = RtmsSource(repo)

    private val asOf = LocalDate.of(2026, 8, 23)
    private val APT = "11680-4929"
    private val AREA = BigDecimal("84.83")

    private fun deal(date: LocalDate, krw: Long, cancelled: Boolean = false) = RtmsDealCacheEntity(
        id = UUID.randomUUID(), aptSeq = APT, exclusiveAreaM2 = AREA, dealDate = date,
        floor = 5, dealAmountKrw = krw, aptName = "개포래미안포레스트", buildYear = 2020,
        sggCode = "11680", umdName = "개포동", isCancelled = cancelled, cancelledOn = null,
        collectedAt = LocalDateTime.of(2026, 8, 23, 19, 30),
    )

    private fun stub(vararg rows: RtmsDealCacheEntity) {
        `when`(
            repo.findByAptSeqAndExclusiveAreaM2AndDealDateBetween(
                anyString(), any() ?: BigDecimal.ZERO, any() ?: LocalDate.EPOCH, any() ?: LocalDate.EPOCH,
            ),
        ).thenReturn(rows.toList())
    }

    private fun realEstate(symbol: String? = APT, area: BigDecimal? = AREA) = Asset.reconstruct(
        id = UUID.randomUUID(), userId = UUID.randomUUID(), accountId = UUID.randomUUID(),
        category = AssetCategory.MANUAL, type = AssetType.REAL_ESTATE,
        sourceType = AssetSourceType.MANUAL, name = "개포래미안포레스트", symbol = symbol,
        quantity = BigDecimal.ONE, purchasePrice = BigDecimal("1500000000"),
        currentValue = BigDecimal("1500000000"), currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT, confidenceLevel = ConfidenceLevel.LOW,
        lastUpdatedAt = LocalDateTime.of(2026, 8, 1, 12, 0),
        createdAt = LocalDateTime.of(2026, 7, 1, 12, 0), memo = null, subType = "OWN",
        loanAmount = null, maturityDate = null, liquidityType = AssetLiquidityType.ILLIQUID,
        areaPyeong = BigDecimal("34.00"), priceAsOf = null, exclusiveAreaM2 = area,
    )

    @Test
    fun `부동산만 담당한다`() {
        assertThat(source.supports(AssetType.REAL_ESTATE)).isTrue()
        assertThat(source.supports(AssetType.GOLD)).isFalse()
        assertThat(source.supports(AssetType.JEONSE)).isFalse()
    }

    // ── 🔴 기간이 아니라 개수로 자른다 ─────────────────────────────────────

    /**
     * **이 어댑터의 존재 이유다.** 오래된 거래가 섞이면 상승장에서 시세가 뒤처진다 —
     * 실측 p50이 +7.7%다. 최근 5건만 쓰면 그 뒤처짐이 사라진다.
     */
    @Test
    fun `거래가 많으면 최근 다섯 건만 쓴다`() {
        // 오래된 싼 거래 5건 + 최근 비싼 거래 5건
        stub(
            deal(LocalDate.of(2025, 9, 10), 1_000_000_000),
            deal(LocalDate.of(2025, 10, 10), 1_000_000_000),
            deal(LocalDate.of(2025, 11, 10), 1_000_000_000),
            deal(LocalDate.of(2025, 12, 10), 1_000_000_000),
            deal(LocalDate.of(2026, 1, 10), 1_000_000_000),
            deal(LocalDate.of(2026, 5, 10), 1_500_000_000),
            deal(LocalDate.of(2026, 6, 10), 1_500_000_000),
            deal(LocalDate.of(2026, 7, 10), 1_500_000_000),
            deal(LocalDate.of(2026, 8, 1), 1_500_000_000),
            deal(LocalDate.of(2026, 8, 20), 1_500_000_000),
        )

        val v = source.valuate(realEstate(), asOf)!!

        // 전체 10건의 중앙값은 12.5억이다. 최근 5건만 쓰면 15억이다.
        assertThat(v.valuationKrw).isEqualByComparingTo("1500000000")
    }

    /** 거래가 적은 단지는 오래된 것까지 거슬러 표본을 확보한다 */
    @Test
    fun `거래가 적으면 오래된 것까지 쓴다`() {
        stub(
            deal(LocalDate.of(2025, 10, 1), 900_000_000),
            deal(LocalDate.of(2026, 2, 1), 1_000_000_000),
            deal(LocalDate.of(2026, 7, 1), 1_100_000_000),
        )

        val v = source.valuate(realEstate(), asOf)!!

        assertThat(v.valuationKrw).isEqualByComparingTo("1000000000")
    }

    /** 창은 12개월이다 — 짧으면 표본이 무너지고 길면 값이 뒤처진다 */
    @Test
    fun `열두 달 창으로 조회한다`() {
        stub(deal(LocalDate.of(2026, 8, 1), 1_000_000_000))

        source.valuate(realEstate(), asOf)

        verify(repo).findByAptSeqAndExclusiveAreaM2AndDealDateBetween(
            APT, AREA, LocalDate.of(2025, 8, 23), asOf,
        )
    }

    // ── 산출 불가는 null이다 ───────────────────────────────────────────────

    /** 실측 12개월 표본의 중앙이 2건이라 **절반은 여기 걸린다. 그게 정상 경로다** */
    @Test
    fun `표본이 세 건 미만이면 null이다`() {
        stub(deal(LocalDate.of(2026, 8, 1), 1_000_000_000),
             deal(LocalDate.of(2026, 7, 1), 1_000_000_000))

        assertThat(source.valuate(realEstate(), asOf)).isNull()
    }

    /** 선택 UI를 안 거치고 손으로 등록한 자산은 매칭할 키가 없다 */
    @Test
    fun `단지 식별자가 없으면 조회하지 않는다`() {
        assertThat(source.valuate(realEstate(symbol = null), asOf)).isNull()

        verify(repo, never()).findByAptSeqAndExclusiveAreaM2AndDealDateBetween(
            anyString(), any() ?: BigDecimal.ZERO, any() ?: LocalDate.EPOCH, any() ?: LocalDate.EPOCH,
        )
    }

    @Test
    fun `전용면적이 없으면 조회하지 않는다`() {
        assertThat(source.valuate(realEstate(area = null), asOf)).isNull()
    }

    // ── 해제 거래 ──────────────────────────────────────────────────────────

    /** **성사되지 않은 가격이다.** 실측 2.6% */
    @Test
    fun `해제 거래는 표본에서 뺀다`() {
        stub(
            deal(LocalDate.of(2026, 8, 20), 3_000_000_000, cancelled = true),
            deal(LocalDate.of(2026, 8, 1), 1_000_000_000),
            deal(LocalDate.of(2026, 7, 1), 1_000_000_000),
            deal(LocalDate.of(2026, 6, 1), 1_000_000_000),
        )

        val v = source.valuate(realEstate(), asOf)!!

        assertThat(v.valuationKrw).isEqualByComparingTo("1000000000")
    }

    /** 해제를 빼고 나서 미달이면 그때도 null이다 */
    @Test
    fun `해제를 뺀 뒤 미달이면 null이다`() {
        stub(
            deal(LocalDate.of(2026, 8, 20), 1_000_000_000, cancelled = true),
            deal(LocalDate.of(2026, 8, 1), 1_000_000_000),
            deal(LocalDate.of(2026, 7, 1), 1_000_000_000),
        )

        assertThat(source.valuate(realEstate(), asOf)).isNull()
    }

    // ── 기준일·신뢰도 ─────────────────────────────────────────────────────

    /**
     * **평가일이 아니라 가장 최근 거래일이다.** 부동산은 몇 달씩 거래가 없는 게 정상이라
     * 둘이 크게 벌어질 수 있고, 화면이 그걸 말해야 한다.
     */
    @Test
    fun `기준일은 가장 최근 거래일이다`() {
        stub(deal(LocalDate.of(2026, 3, 15), 1_000_000_000),
             deal(LocalDate.of(2026, 2, 1), 1_000_000_000),
             deal(LocalDate.of(2026, 1, 1), 1_000_000_000))

        val v = source.valuate(realEstate(), asOf)!!

        assertThat(v.priceAsOf).isEqualTo(LocalDate.of(2026, 3, 15))
        assertThat(v.priceAsOf).isNotEqualTo(asOf)
    }

    /** 체결가다 — 시계(호가)와 성격이 다르다 */
    @Test
    fun `가격 성격은 체결가다`() {
        stub(deal(LocalDate.of(2026, 8, 1), 1_000_000_000),
             deal(LocalDate.of(2026, 7, 1), 1_000_000_000),
             deal(LocalDate.of(2026, 6, 1), 1_000_000_000))

        assertThat(source.valuate(realEstate(), asOf)!!.priceBasis).isEqualTo(PriceBasis.TRADE)
    }

    /**
     * **HIGH가 없다.** 체결가여도 부동산은 개별성이 크다 — 같은 평형도 층·향·리모델링에
     * 따라 갈린다. 중앙값이 그 집의 값이라고 말할 수 없다.
     */
    @Test
    fun `신뢰도는 MEDIUM이 최대다`() {
        stub(*(1..20).map { deal(LocalDate.of(2026, 8, 1).minusDays(it.toLong()), 1_000_000_000) }
            .toTypedArray())

        assertThat(source.valuate(realEstate(), asOf)!!.confidence).isEqualTo(ConfidenceLevel.MEDIUM)
    }

    @Test
    fun `표본이 다섯 건 미만이면 LOW다`() {
        stub(deal(LocalDate.of(2026, 8, 1), 1_000_000_000),
             deal(LocalDate.of(2026, 7, 1), 1_000_000_000),
             deal(LocalDate.of(2026, 6, 1), 1_000_000_000))

        assertThat(source.valuate(realEstate(), asOf)!!.confidence).isEqualTo(ConfidenceLevel.LOW)
    }

    /** 짝수 표본은 가운데 둘의 평균이다 */
    @Test
    fun `짝수 표본의 중앙값`() {
        stub(deal(LocalDate.of(2026, 8, 1), 1_000_000_000),
             deal(LocalDate.of(2026, 7, 1), 1_200_000_000),
             deal(LocalDate.of(2026, 6, 1), 1_400_000_000),
             deal(LocalDate.of(2026, 5, 1), 1_600_000_000))

        assertThat(source.valuate(realEstate(), asOf)!!.valuationKrw)
            .isEqualByComparingTo("1300000000")
    }
}
