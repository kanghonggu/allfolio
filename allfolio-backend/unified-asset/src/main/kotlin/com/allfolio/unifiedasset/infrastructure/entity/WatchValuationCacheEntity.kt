package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * watchpricedata `/api/valuation` 응답 한 건의 사본 (W5).
 *
 * `market_commodity_quote`(금) · `rtms_deals_cache`(부동산)와 같은 자리다 — 셋 다 원본 시세
 * 캐시이고, 평가 결과는 `ua_assets.current_value`에 따로 쓴다.
 *
 * **`(refKey, asOf)`로 이력을 남긴다.** 한 ref당 한 행으로 덮어쓰지 않는 이유는 금과 같다 —
 * 수집이 하루 실패해도 직전 값으로 폴백해야 하고, 과거 화면을 재현할 수 있어야 한다.
 *
 * **`asOf`는 관측일이 아니라 조회일이다** — 30일 창의 끝이다. 그래서 이름을 `priceAsOf`로
 * 짓지 않았다. 자세한 내용은 `WatchValuationClient` KDoc.
 *
 * **`collectedAt`에 DB DEFAULT가 없다 — 앱이 반드시 채운다.** 컨테이너가 UTC이므로
 * 호출자가 주입한 시각을 쓴다(`RtmsDealCacheEntity`와 같은 판단).
 */
@Entity
@Table(
    name = "watch_valuation_cache",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_watch_valuation_cache", columnNames = ["ref_key", "as_of"]),
    ],
)
class WatchValuationCacheEntity(
    @Id
    @Column(name = "id", nullable = false)
    val id: UUID,

    @Column(name = "ref_key", nullable = false, length = 64)
    val refKey: String,

    @Column(name = "as_of", nullable = false)
    val asOf: LocalDate,

    @Column(name = "window_days", nullable = false)
    val windowDays: Short,

    @Column(name = "sample_size", nullable = false)
    val sampleSize: Int,

    /** 원 단위. 평균이 아니라 중앙값이다 — 빈티지 1건이 평균을 5배로 흔든 실측이 설계 7절에 있다 */
    @Column(name = "median_krw", nullable = false)
    val medianKrw: Long,

    @Column(name = "p25_krw")
    val p25Krw: Long? = null,

    @Column(name = "p75_krw")
    val p75Krw: Long? = null,

    @Column(name = "dispersion", precision = 6, scale = 4)
    val dispersion: BigDecimal? = null,

    @Column(name = "official_price_krw")
    val officialPriceKrw: Long? = null,

    @Column(name = "confidence", nullable = false, length = 10)
    val confidence: String,

    @Column(name = "price_basis", nullable = false, length = 10)
    val priceBasis: String,

    @Column(name = "collected_at", nullable = false)
    val collectedAt: LocalDateTime,
)
