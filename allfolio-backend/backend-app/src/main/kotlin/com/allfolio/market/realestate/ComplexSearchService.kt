package com.allfolio.market.realestate

import java.math.BigDecimal

/** 단지 하나의 한 평형. **[exclusiveAreaM2]가 그대로 자산에 저장된다** */
data class ComplexAreaView(
    /** 전용면적(㎡). 소스가 준 값 그대로 — 반올림하면 매칭이 깨진다 */
    val exclusiveAreaM2: BigDecimal,
    /** 참고용 평(㎡ ÷ 3.305785). **저장하지 않는다** — 표시만 한다 */
    val approxPyeong: BigDecimal,
    /** 이 평형의 최근 거래 수. 표본이 얇으면 사용자가 알아야 한다 */
    val dealCount: Int,
)

/** 검색 결과 한 단지 */
data class ComplexView(
    /** 단지일련번호. **`ua_assets.symbol`에 그대로 들어간다** */
    val aptSeq: String,
    val aptName: String,
    val umdName: String,
    val buildYear: Int?,
    /** 이 단지에서 거래된 평형들. 사용자가 여기서 고른다 */
    val areas: List<ComplexAreaView>,
)

/**
 * 검색 결과. **빈 목록의 사유를 함께 준다** (AF-202).
 *
 * 예전에는 `List<ComplexView>`만 돌려줘서, 화면이 빈 결과를 보고 *"최근 실거래가 없는
 * 단지입니다"*라고 말했다. 그런데 그 문장이 참인 경우는 셋 중 하나뿐이다:
 *
 * 1. 그 시군구를 **한 번도 수집하지 않았다** ← 2026-09-09 데모에서 실제로 이것이었다
 * 2. 수집했는데 검색어에 맞는 단지가 없다
 * 3. 그 단지에 최근 거래가 없다
 *
 * 1번을 3번이라고 말하면 **사용자가 "우리 아파트는 실거래가 없구나"로 잘못 배운다.**
 * 전국 250여 시군구 중 수집된 곳이 3개뿐이던 시점이라 대부분의 사용자가 1번을 만났다.
 */
data class ComplexSearchResult(
    /**
     * 이 시군구를 한 번이라도 수집했는가.
     *
     * `false`면 [complexes]가 빈 것은 **데이터가 없어서지 그 지역에 거래가 없어서가 아니다.**
     */
    val collected: Boolean,
    val complexes: List<ComplexView>,
)

/**
 * 단지·평형 검색 (R2).
 *
 * ## 왜 캐시에서만 찾는가
 *
 * 국토부 API는 **단지로 물을 수 없다** — `(시군구, 년월)`이 유일한 질의 단위다. 그래서
 * "단지 목록"이라는 것이 상류에 없고, **우리가 받아 둔 거래에서 역으로 뽑는 수밖에 없다.**
 *
 * 그 결과 **거래가 없었던 단지는 검색에 안 나온다.** 그게 맞다 — 실거래가 없으면 평가도
 * 못 한다. 사용자가 못 찾는다면 그건 "그 단지는 자동 평가가 안 된다"는 뜻이고,
 * 화면이 그렇게 말해야 한다.
 *
 * ## 평형을 함께 준다
 *
 * **이것이 이 API의 존재 이유다.** 사용자가 전용면적을 손으로 적으면 84.97과 84.93이
 * 갈리지 않는다 — 같은 단지 안에서 평형이 1㎡ 미만으로 붙어 있는 쌍이 실측 146건이다.
 * 목록에서 고르게 하면 **API가 준 값이 그대로 저장되어 정확 일치가 성립한다.**
 *
 * 평(坪)은 참고로만 붙인다. 저장하지 않는다 — 그 값으로 매칭하면 다시 모호해진다.
 */
interface ComplexSearchService {
    /**
     * @param query 단지명 일부. 비면 [sggCode] 전체를 준다
     * @param sggCode 법정동 앞 5자리. **필수다** — 전국을 훑으면 "래미안"에 수백 개가 걸린다
     */
    fun search(sggCode: String, query: String?, limit: Int = DEFAULT_LIMIT): ComplexSearchResult

    companion object {
        const val DEFAULT_LIMIT = 20

        /** 1평 = 3.305785㎡ (3.3058로 반올림하지 않는다 — 표시값이 평형마다 어긋난다) */
        val M2_PER_PYEONG: BigDecimal = BigDecimal("3.305785")
    }
}
