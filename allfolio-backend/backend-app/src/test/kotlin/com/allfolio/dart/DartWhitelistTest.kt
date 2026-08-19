package com.allfolio.dart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 값은 2026-08-11~08-18 실측 list.json 8,667건(상장사 5,394건)에서 가져왔다.
 * `tierOf`의 입력은 반드시 [DartReportName.normalize]를 거친 이름이다 — 원문을 그대로
 * 넣으면 아래아(U+318D) 때문에 Tier 1 키워드(가운뎃점 U+00B7 기준)가 통째로 안 잡힌다.
 */
class DartWhitelistTest {

    private fun tierOf(raw: String) = DartWhitelist.tierOf(DartReportName.normalize(raw))

    @Test
    fun `원문을 그대로 넣으면 안 된다 — 정규화를 거쳐야 잡힌다`() {
        // 원안이 틀렸던 지점. 아래아가 든 원문은 판정기가 직접 받으면 못 잡는다.
        assertThat(DartWhitelist.tierOf("단일판매ㆍ공급계약체결")).isNull()
        assertThat(tierOf("단일판매ㆍ공급계약체결")).isEqualTo(1)
    }

    @Test
    fun `Tier 1은 주가에 직결되는 결정이다`() {
        assertThat(tierOf("유상증자결정")).isEqualTo(1)
        assertThat(tierOf("자기주식취득결정")).isEqualTo(1)
        assertThat(tierOf("유형자산취득결정              ")).isEqualTo(1)
        assertThat(tierOf("최대주주변경")).isEqualTo(1)
    }

    @Test
    fun `Tier 2는 재무와 실적이다`() {
        // 정규화 전에는 0건이던 키워드다 — 원문(아래아)으로 넣으면 안 잡히는 걸 여기서도 확인한다.
        assertThat(tierOf("현금ㆍ현물배당결정              (분기배당)")).isEqualTo(2)
        assertThat(tierOf("매출액또는손익구조30%(대규모법인15%)이상변동")).isEqualTo(2)
    }

    @Test
    fun `Tier 3은 위험이다`() {
        assertThat(tierOf("소송등의제기ㆍ신청")).isEqualTo(3)
        assertThat(tierOf("조회공시요구(풍문또는보도)에대한답변")).isEqualTo(3)
    }

    @Test
    fun `Tier 4는 임원 소유변동 트리거다`() {
        assertThat(tierOf("임원ㆍ주요주주특정증권등소유상황보고서")).isEqualTo(4)
    }

    @Test
    fun `Tier 5는 정기보고서다`() {
        // 제출 시즌에 피드를 덮으므로 분리했다. 실측 6영업일 상장사 5,394건 중 2,846건.
        assertThat(tierOf("반기보고서 (2026.06)")).isEqualTo(5)
        assertThat(tierOf("[기재정정]반기보고서 (2026.06)")).isEqualTo(5)
        assertThat(tierOf("사업보고서 (2025.12)")).isEqualTo(5)
        assertThat(tierOf("감사보고서 (2025.12)")).isEqualTo(5)
    }

    @Test
    fun `해당 없으면 null이고 저장은 된다`() {
        // 실측 미적중 상위. 저장은 하되 피드에 안 나간다 — 설계 원칙 4.
        assertThat(tierOf("지급수단별ㆍ지급기간별지급금액및분쟁조정기구에관한사항")).isNull()
        assertThat(tierOf("기업설명회(IR)개최")).isNull()
        assertThat(tierOf("주주총회소집공고")).isNull()
        assertThat(tierOf("증권발행실적보고서")).isNull()
    }

    @Test
    fun `낮은 Tier가 이긴다`() {
        // 한 이름이 여러 Tier에 걸리면 더 중요한 쪽으로 간다
        assertThat(DartWhitelist.tierOf("자기주식취득결정 및 반기보고서")).isEqualTo(1)
    }

    @Test
    fun `isMaterial은 Tier가 있으면 참이고 Tier 5도 포함한다`() {
        assertThat(DartWhitelist.isMaterial(5)).isTrue()
        assertThat(DartWhitelist.isMaterial(null)).isFalse()
    }
}
