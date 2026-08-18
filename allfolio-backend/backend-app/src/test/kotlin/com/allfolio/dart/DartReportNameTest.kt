package com.allfolio.dart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 값은 전부 2026-08-11~08-18 실측 응답 8,667건에서 가져왔다.
 * 지어낸 입력이 하나도 없다 — 지어내면 실제로 오는 형태를 못 잡는다.
 *
 * 구분자 문자는 유니코드 이스케이프로 적는다. 편집기·터미널을 거치며 문자가
 * 바뀌는 사고를 막기 위함이다 (U+318D 아래아, U+30FB 가타카나 중점, U+00B7 가운뎃점).
 */
class DartReportNameTest {

    private companion object {
        /** DART가 실제로 report_nm 구분자로 쓰는 문자. U+00B7(가운뎃점)이 아니다. */
        const val ARAEA = 'ㆍ' // ㆍ HANGUL LETTER ARAEA
        const val KATAKANA_MIDDLE_DOT = '・' // ・ KATAKANA MIDDLE DOT
        const val MIDDLE_DOT = '·' // · MIDDLE DOT — 화이트리스트가 쓰는 문자, 실측 8,667건 중 1회
    }

    @Test
    fun `구분자 코드포인트를 확인한다`() {
        // 이 테스트 자체가 틀린 문자를 쓰고 있지 않은지를 보장한다.
        assertThat(ARAEA.code).isEqualTo(0x318D)
        assertThat(KATAKANA_MIDDLE_DOT.code).isEqualTo(0x30FB)
        assertThat(MIDDLE_DOT.code).isEqualTo(0x00B7)
    }

    @Test
    fun `뒤에 붙은 공백을 떼어낸다`() {
        // 실측 887건(10%)이 이 형태다
        assertThat(DartReportName.normalize("단일판매${ARAEA}공급계약체결              "))
            .isEqualTo("단일판매${MIDDLE_DOT}공급계약체결")
    }

    @Test
    fun `아래아를 가운뎃점으로 통일한다`() {
        // DART는 U+318D(ㆍ)를 쓴다. 실측 2,856회 대 U+00B7(·) 1회.
        assertThat(DartReportName.normalize("현금${ARAEA}현물배당결정")).isEqualTo("현금${MIDDLE_DOT}현물배당결정")
        assertThat(DartReportName.normalize("임원${ARAEA}주요주주특정증권등소유상황보고서"))
            .isEqualTo("임원${MIDDLE_DOT}주요주주특정증권등소유상황보고서")
    }

    @Test
    fun `가타카나 중점도 통일한다`() {
        assertThat(DartReportName.normalize("단일판매${KATAKANA_MIDDLE_DOT}공급계약체결"))
            .isEqualTo("단일판매${MIDDLE_DOT}공급계약체결")
    }

    @Test
    fun `이미 가운뎃점이면 그대로 둔다`() {
        assertThat(DartReportName.normalize("현금${MIDDLE_DOT}현물배당결정")).isEqualTo("현금${MIDDLE_DOT}현물배당결정")
    }

    @Test
    fun `접두어를 뗀다`() {
        // 실측 5종: [기재정정]875 [첨부정정]29 [첨부추가]20 [발행조건확정]4 [변경등록]1
        assertThat(DartReportName.normalize("[기재정정]반기보고서 (2026.06)")).isEqualTo("반기보고서 (2026.06)")
        assertThat(DartReportName.normalize("[첨부정정]사업보고서")).isEqualTo("사업보고서")
        assertThat(DartReportName.normalize("[발행조건확정]증권신고서")).isEqualTo("증권신고서")
        assertThat(DartReportName.normalize("[변경등록]투자설명서")).isEqualTo("투자설명서")
    }

    @Test
    fun `문서에 없던 새 접두어도 뗀다`() {
        // 열거가 아니라 패턴으로 잡는 이유. 원안은 5종 중 2종을 몰랐다.
        assertThat(DartReportName.normalize("[처음보는접두어]반기보고서")).isEqualTo("반기보고서")
    }

    @Test
    fun `접두어를 뗀 뒤 남은 앞 공백도 없앤다`() {
        assertThat(DartReportName.normalize("[기재정정] 반기보고서   ")).isEqualTo("반기보고서")
    }

    @Test
    fun `본문 중간의 대괄호는 접두어가 아니다`() {
        assertThat(DartReportName.normalize("증권신고서[지분증권]")).isEqualTo("증권신고서[지분증권]")
    }

    @Test
    fun `접두어 유무를 따로 알려준다`() {
        assertThat(DartReportName.hasCorrectionPrefix("[기재정정]반기보고서")).isTrue()
        assertThat(DartReportName.hasCorrectionPrefix("반기보고서")).isFalse()
        assertThat(DartReportName.hasCorrectionPrefix("증권신고서[지분증권]")).isFalse()
    }

    @Test
    fun `실측 예시들이 3단을 모두 통과한다`() {
        // 실측 report_nm 원문 그대로. 접두어+뒤공백이 함께 있는 경우도 실제로 온다.
        assertThat(DartReportName.normalize("자기주식처분결과보고서")).isEqualTo("자기주식처분결과보고서")
        assertThat(DartReportName.normalize("감사보고서 (2025.12)")).isEqualTo("감사보고서 (2025.12)")
        assertThat(DartReportName.normalize("지급수단별${ARAEA}지급기간별지급금액및분쟁조정기구에관한사항"))
            .isEqualTo("지급수단별${MIDDLE_DOT}지급기간별지급금액및분쟁조정기구에관한사항")
        assertThat(DartReportName.normalize("주주총회소집결의              (임시주주총회)"))
            .isEqualTo("주주총회소집결의              (임시주주총회)")
        assertThat(DartReportName.normalize("[기재정정]투자판단관련주요경영사항              "))
            .isEqualTo("투자판단관련주요경영사항")
    }

    @Test
    fun `빈 문자열과 공백만 있는 문자열에서 예외가 나지 않는다`() {
        assertThat(DartReportName.normalize("")).isEqualTo("")
        assertThat(DartReportName.normalize("   ")).isEqualTo("")
        assertThat(DartReportName.hasCorrectionPrefix("")).isFalse()
        assertThat(DartReportName.hasCorrectionPrefix("   ")).isFalse()
    }

    // 실측 0건 — 방어적 케이스.
    // 실측 8,667건의 접두어 개수 분포는 0개 7,738 · 1개 929 · 2개 이상 0으로, 중첩 접두어는
    // 한 번도 관측되지 않았다. 그래도 정정공시 묶기(설계 7절)와 "열거 대신 패턴" 원칙이
    // "한 겹만 뗀다"는 가정에 기대면 안 되므로, 관측 여부와 무관하게 방어적으로 다룬다.
    @Test
    fun `중첩된 접두어를 전부 뗀다`() {
        assertThat(DartReportName.normalize("[기재정정][첨부추가]반기보고서")).isEqualTo("반기보고서")
        assertThat(DartReportName.hasCorrectionPrefix("[기재정정][첨부추가]반기보고서")).isTrue()
    }

    // 실측 0건 — 불변식 고정용.
    // 접두어 앞에 오는 공백은 실측 8,667건에서 한 번도 관측되지 않았다. 그래도 문서화 주석이
    // "1단계(trim)가 없으면 결과가 달라진다"고 말하는 이상, 그 말을 뒷받침하는 테스트가 있어야
    // 나중에 "trim이 중복이네" 하고 지우는 회귀를 막을 수 있다.
    @Test
    fun `접두어 앞의 공백도 trim이 없애 접두어 인식을 가능하게 한다`() {
        assertThat(DartReportName.normalize("  [기재정정]반기보고서")).isEqualTo("반기보고서")
    }
}
