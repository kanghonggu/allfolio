package com.allfolio.dart

/**
 * `report_nm` 정규화. 순서가 있는 3단이고, 순서를 바꾸면 결과가 달라진다.
 *
 * 1. trim — 실측 887건(10%)에 뒤 공백이 붙어 온다
 * 2. 접두어 반복 제거 — `^\[…\]`를 매치가 없을 때까지 되풀이해서 뗀다. 열거하지 않는 이유는
 *    원안이 실측 5종([기재정정]875 [첨부정정]29 [첨부추가]20 [발행조건확정]4 [변경등록]1) 중
 *    2종을 몰랐기 때문이다. 문서에 없던 접두어가 또 나와도 이 패턴이면 잡는다.
 *    **중첩 접두어(`[기재정정][첨부추가]…`)는 실측 8,667건에서 0건이었다** — 접두어 개수 분포는
 *    0개 7,738 · 1개 929 · 2개 이상 0. 그래도 반복 제거로 짠 이유는 관측이 아니라 설계 일관성이다:
 *    설계 7절의 정정공시 묶기는 원본·정정본이 같은 `report_nm_norm`으로 떨어지는 데 의존하고,
 *    "열거 대신 패턴"이라는 이 태스크의 원래 근거("열거하면 또 샌다")는 "한 겹만 뗀다"에도
 *    똑같이 적용된다 — 한 겹도 결국 열거의 일종이다. DART가 기재정정과 첨부추가를 독립적으로
 *    붙이는 이상 조합이 나올 여지는 남는다.
 * 3. 구분자 통일 — DART는 U+318D(ㆍ 한글 아래아)를 쓰는데 화이트리스트는 U+00B7(· 가운뎃점)를
 *    쓴다. 실측 8,667건에서 아래아 2,856회 대 가운뎃점 1회. U+30FB(・ 가타카나 중점)도 함께 통일한다.
 *
 * **3단계를 지우지 말 것.** 이것이 없으면 Tier 1의 단일판매·공급계약체결이 통째로 안 잡힌다.
 */
object DartReportName {

    private val PREFIX = Regex("""^\[[^\]]+]""")

    /** U+318D 아래아 · U+30FB 가타카나 중점 → U+00B7 가운뎃점 */
    private const val SEPARATORS = "ㆍ・"
    private const val MIDDLE_DOT = '·'

    fun normalize(raw: String): String {
        var s = raw.trim()
        while (PREFIX.containsMatchIn(s)) {
            s = PREFIX.replace(s, "").trim()
        }
        return s.map { if (it in SEPARATORS) MIDDLE_DOT else it }.joinToString("")
    }

    /**
     * `normalize`가 접두어를 뗄지 여부와 반드시 일치해야 한다 — 어긋나면 같은 행에 대해
     * `is_correction`과 `report_nm_norm`이 서로 다른 이야기를 하게 된다. `normalize`의 반복
     * 루프도 매 회 이 조건(`PREFIX`가 맨 앞에 매치되는지)으로 계속 여부를 판단하므로,
     * 첫 겹 존재 여부만 확인하는 이 검사와 자연히 일치한다.
     */
    fun hasCorrectionPrefix(raw: String): Boolean = PREFIX.containsMatchIn(raw.trim())
}
