package com.allfolio.market.realestate

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 국토교통부 아파트 매매 실거래 한 건.
 *
 * **부동산 평가의 표본 단위**다. 단지(`aptSeq`)와 전용면적(`exclusiveAreaM2`)으로 묶어
 * 중앙값을 낸다 — `ua_assets`의 `symbol`·`exclusive_area_m2`가 그 짝이다.
 *
 * ## 이 표본은 시계보다 훨씬 얇다
 *
 * 실측(2026-08-21, 강남·분당 3개월 2,269건): 단지 429곳 · 단지당 면적 2.4종 →
 * **(단지, 면적) 조합당 3개월에 약 2건**이다. 시계가 30일 창에 ref당 3건을 요구한 것과
 * 다른 세계다. 창을 12개월쯤 잡아야 표본이 서고, 그래도 상당수 조합은 미달이라
 * **`null` 반환이 정상 경로**다.
 */
data class RtmsDeal(
    /** 단지일련번호 `{시군구5자리}-{일련}` (예: `11110-132`). 실측 391/391이 이 형식이다 */
    val aptSeq: String,
    val aptName: String,
    /**
     * 전용면적(㎡).
     *
     * **정확 일치로 매칭한다 — 허용오차를 두지 말 것.** 같은 단지 안에서 평형이
     * 1㎡ 미만으로 붙어 있는 쌍이 실측 146건이다(개포자이프레지던스 `84.6`↔`84.75`,
     * 개포래미안포레스트 `84.83`↔`84.86`). ±0.5㎡만 둬도 다른 평형이 섞인다.
     */
    val exclusiveAreaM2: BigDecimal,
    /** 계약일 */
    val dealDate: LocalDate,
    /** 거래금액(원). 응답은 **만원 단위 콤마 문자열**이라 파서가 환산한다 */
    val dealAmountKrw: Long,
    val floor: Int,
    val buildYear: Int?,
    /** 법정동 시군구 코드 5자리 */
    val sggCode: String,
    val umdName: String,
    /**
     * 해제(취소)된 거래인지.
     *
     * **중앙값에 넣으면 안 된다.** 실측 391건 중 5건(1.3%)이 해제였다. 같은 거래가 처음엔
     * 정상으로 왔다가 나중에 해제로 바뀌므로, 캐시는 **덮어쓰기(upsert)**여야 한다 —
     * 한 번 넣고 마는 구조면 취소된 거래가 영원히 시세에 남는다.
     */
    val cancelled: Boolean,
    /** 해제일. 응답이 **두 자리 연도**(`26.07.13`)로 준다 */
    val cancelledOn: LocalDate?,
)
