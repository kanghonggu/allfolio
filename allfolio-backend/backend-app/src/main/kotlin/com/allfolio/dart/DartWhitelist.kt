package com.allfolio.dart

/**
 * `material_tier` 판정. **입력은 반드시 [DartReportName.normalize]를 거친 이름이다** —
 * 원문을 넣으면 아래아(U+318D) 때문에 Tier 1의 단일판매·공급계약체결 등이 통째로 빠진다.
 * 아래 키워드의 구분자는 전부 U+00B7(가운뎃점)이다.
 *
 * v1은 `pblntf_detail_ty` 코드가 아니라 키워드 부분일치다. 코드값이 `list.json` 응답에
 * 아예 없기도 하고(실측 필드 9개), 키워드는 로그를 보며 즉시 튜닝할 수 있다.
 *
 * 값은 2026-08-11~08-18 실측 list.json 8,667건(상장사 5,394건) 기준이다. 정규화 수정 +
 * Tier 5 분리 후 상장사 Tier 분포: T1 211 · T2 49 · T3 111 · T4 290 · T5 2,846 · 미해당 1,887.
 */
object DartWhitelist {

    /**
     * `tierOf`는 리스트를 앞에서부터 순회하며 첫 매치를 반환한다 — **순서가 곧 우선순위**다.
     * 한 이름이 여러 Tier의 키워드에 걸리면(예: "자기주식취득결정 및 반기보고서") 리스트에서
     * 먼저 나오는, 즉 숫자가 낮은 Tier가 이긴다. 순서를 바꾸면 이 규칙이 깨진다.
     */
    private val TIERS: List<Pair<Short, List<String>>> = listOf(
        1.toShort() to listOf(
            "유상증자결정", "무상증자결정", "감자결정",
            "전환사채권발행결정", "신주인수권부사채권발행결정", "교환사채권발행결정",
            "자기주식취득결정", "자기주식처분결정", "주식소각결정",
            "회사합병결정", "회사분할결정", "영업양수도결정",
            "단일판매·공급계약체결", "유형자산취득결정", "유형자산처분결정",
            "타법인주식및출자증권취득결정", "최대주주변경",
        ),
        2.toShort() to listOf("매출액또는손익구조", "현금·현물배당결정"),
        3.toShort() to listOf(
            "소송등의제기·신청", "부도발생", "회생절차개시신청", "자본잠식",
            "관리종목지정", "상장폐지", "매매거래정지", "횡령·배임", "조회공시요구",
        ),
        4.toShort() to listOf("임원·주요주주특정증권등소유상황보고서"),
        5.toShort() to listOf("사업보고서", "반기보고서", "분기보고서", "감사보고서"),
    )

    /** ② elestock 호출 대상을 가리는 Tier */
    const val TIER_INSIDER: Short = 4

    fun tierOf(normalized: String): Short? =
        TIERS.firstOrNull { (_, keywords) -> keywords.any { it in normalized } }?.first

    fun isMaterial(tier: Short?): Boolean = tier != null
}
