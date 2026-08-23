package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 국토부 아파트 매매 실거래 한 건 (A1 v3).
 *
 * **자연키가 `(단지, 전용면적, 계약일, 층, 금액)`이다** — 응답에 거래 고유 ID가 없다.
 * 같은 단지·같은 층·같은 면적·같은 날·같은 금액인 거래가 둘일 수는 없다.
 *
 * **값 필드가 var인 이유는 덮어쓰기 때문이다.** 같은 거래가 처음엔 정상으로 왔다가 나중에
 * 해제로 바뀐다(실측 2,698건 중 71건 2.6%). 한 번 넣고 마는 구조면 취소된 거래가 영원히
 * 시세에 남는다.
 *
 * **`dealAmountKrw`는 원 단위 `BIGINT`다.** 응답은 만원 단위 콤마 문자열(`"55,000"`=5.5억)이고
 * 파서가 환산해 넣는다. 문자열이나 만원 단위로 저장하면 읽는 쪽마다 환산을 다시 해야 하고,
 * 한 곳이라도 빠뜨리면 10,000배 틀린 값이 조용히 나간다.
 *
 * **`exclusiveAreaM2`를 반올림하지 않는다.** 매칭이 정확 일치인데, 같은 단지 안에 평형이
 * 1㎡ 미만으로 붙어 있는 쌍이 실측 146건이다(`84.6`↔`84.75` · `84.83`↔`84.86`).
 *
 * **`collectedAt`에 DB DEFAULT가 없다 — 앱이 반드시 채운다.** 컨테이너가 UTC이므로
 * 호출자가 주입한 시각을 쓴다(수집 서비스가 한 번만 정해 전 행에 같은 값으로 넣는다).
 */
@Entity
@Table(
    name = "rtms_deals_cache",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_rtms_deals_cache",
            columnNames = ["apt_seq", "exclusive_area_m2", "deal_date", "floor", "deal_amount_krw"],
        ),
    ],
)
class RtmsDealCacheEntity(
    @Id
    @Column(name = "id", nullable = false)
    val id: UUID,

    @Column(name = "apt_seq", nullable = false, length = 20)
    val aptSeq: String,

    @Column(name = "exclusive_area_m2", nullable = false, precision = 10, scale = 4)
    val exclusiveAreaM2: BigDecimal,

    @Column(name = "deal_date", nullable = false)
    val dealDate: LocalDate,

    @Column(name = "floor", nullable = false)
    val floor: Int,

    @Column(name = "deal_amount_krw", nullable = false)
    val dealAmountKrw: Long,

    // ── 아래는 다시 수집하면 덮는다 ─────────────────────────────────────────
    // 단지명이 바뀌거나(재건축·명칭 변경) 해제 상태가 붙으면 첫 수집 당시 값이 굳으면 안 된다.

    @Column(name = "apt_name", nullable = false, length = 200)
    var aptName: String,

    @Column(name = "build_year")
    var buildYear: Int?,

    @Column(name = "sgg_code", nullable = false, length = 5)
    var sggCode: String,

    @Column(name = "umd_name", nullable = false, length = 100)
    var umdName: String,

    /** 해제(취소) 거래. **중앙값에서 뺄 것** — 성사되지 않은 가격이다 */
    @Column(name = "is_cancelled", nullable = false)
    var isCancelled: Boolean,

    /** 해제일. 응답은 두 자리 연도(`26.07.13`)로 준다 */
    @Column(name = "cancelled_on")
    var cancelledOn: LocalDate?,

    @Column(name = "collected_at", nullable = false)
    var collectedAt: LocalDateTime,
)

/** `(시군구, 년월)` 복합 키 */
data class RtmsFetchLogId(
    val sggCode: String = "",
    val dealYm: String = "",
) : Serializable

/**
 * 어느 `(시군구, 년월)`을 이미 받아 왔는지.
 *
 * **거래 0건과 미수집을 가르는 표다.** 거래 표만 있으면 "그 달에 거래가 없었다"와
 * "우리가 안 물어봤다"가 구분되지 않는다. `dealCount = 0`도 유효한 기록이고,
 * **행이 없는 것만이 미수집**이다.
 *
 * `apiCalls`는 페이징 포함 실제 호출 수다 — **일 1,000콜 예산**을 뒤늦게 따질 근거가 된다.
 */
@Entity
@Table(name = "rtms_fetch_log")
@IdClass(RtmsFetchLogId::class)
class RtmsFetchLogEntity(
    @Id
    @Column(name = "sgg_code", nullable = false, length = 5)
    val sggCode: String,

    /** `yyyyMM`. API 파라미터 형식 그대로 둔다 — 변환을 한 번 덜 한다 */
    @Id
    @Column(name = "deal_ym", nullable = false, length = 6)
    val dealYm: String,

    @Column(name = "deal_count", nullable = false)
    var dealCount: Int,

    @Column(name = "api_calls", nullable = false)
    var apiCalls: Int,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: LocalDateTime,
)
