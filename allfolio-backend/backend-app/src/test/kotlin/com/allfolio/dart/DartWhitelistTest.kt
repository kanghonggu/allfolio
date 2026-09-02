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

    // ── S13 튜닝 (2026-09-02) ─────────────────────────────────────
    // 아래 값은 전부 운영 실측이다. 2026-08-18~09-01 상장사 5,060건.

    @Test
    fun `행정적 사유로 인한 거래정지는 위험이 아니다`() {
        // T3 153건 중 87건이 거래정지·상장폐지 키워드였고 그중 47건이 이 부류다.
        // 액면병합·액면분할·주식병합·전자등록은 결제 사무를 위한 정지지 위험 신호가 아니다.
        assertThat(tierOf("주권매매거래정지해제              (액면병합 주권 변경상장)")).isNull()
        assertThat(tierOf("주권매매거래정지              (주식의 병합, 분할 등 전자등록 변경, 말소)")).isNull()
        assertThat(tierOf("주권매매거래정지해제              (액면분할 주권 변경상장)")).isNull()
        assertThat(tierOf("주권매매거래정지해제              (주식병합(무액면주식) 주권 변경상장)")).isNull()
        assertThat(tierOf("주권매매거래정지기간변경              (액면병합 주권 변경상장)")).isNull()
        assertThat(tierOf("주권매매거래정지해제              (우회상장 미해당)")).isNull()
    }

    @Test
    fun `해제라고 안전한 것이 아니다 — 정리매매 개시가 반례다`() {
        // 🔴 이 테스트가 이 변경의 핵심이다. 원래 코드 주석은 "정지해제는 정지가 풀린 것"이라
        // 적고 부정형(`해제`)을 걸러내는 방향을 제안했는데, 그렇게 고치면 아래가 사라진다 —
        // 이 표에서 가장 위험한 사건이다. 가르는 축은 정지/해제가 아니라 괄호 안의 사유다.
        assertThat(tierOf("주권매매거래정지해제              (상장폐지에 따른 정리매매 개시)")).isEqualTo(3)

        // 정지 쪽도 사유가 위험이면 남는다
        assertThat(tierOf("주권매매거래정지              (상장폐지 사유발생)")).isEqualTo(3)
        assertThat(tierOf("주권매매거래정지              (상장적격성 실질심사 대상(사유발생))")).isEqualTo(3)
        assertThat(tierOf("주권매매거래정지              (투자자 보호)")).isEqualTo(3)
        assertThat(tierOf("주권매매거래정지기간변경              (개선기간 부여)")).isEqualTo(3)
        assertThat(tierOf("매매거래정지및정지해제(중요내용공시)")).isEqualTo(3)
        assertThat(tierOf("기타시장안내              (시가총액 미달에 따른 상장폐지 우려 관련 안내)")).isEqualTo(3)
    }

    @Test
    fun `행정적 사유가 있어도 다른 위험 키워드가 있으면 남는다`() {
        // 🔴 실측에는 없는 조합이라 **일부러 지어낸 이름이다.** 그래도 고정하는 이유는
        // `isAdministrativeHalt`의 "T3가 된 근거가 거래정지뿐인가" 가드가 이 케이스에만
        // 걸리기 때문이다 — 이 테스트가 없으면 가드를 통째로 지워도 아무 테스트가 안 죽는다.
        assertThat(tierOf("주권매매거래정지              (액면병합 주권 변경상장, 횡령ㆍ배임 발생)"))
            .isEqualTo(3)
    }

    @Test
    fun `같은 사건인데 이름만 다른 것을 놓치지 않는다`() {
        // 화이트리스트에 이미 있는 개념인데 DART가 다른 표현을 써서 빠져 있던 것들.
        // 취득/처분만 알고 양수/양도를 몰랐다.
        assertThat(tierOf("주요사항보고서(타법인주식및출자증권양수결정)")).isEqualTo(1)
        assertThat(tierOf("주요사항보고서(타법인주식및출자증권양도결정)")).isEqualTo(1)
        assertThat(tierOf("타법인주식및출자증권처분결정")).isEqualTo(1)
        assertThat(tierOf("주요사항보고서(유형자산양수결정)")).isEqualTo(1)
        assertThat(tierOf("주요사항보고서(유형자산양도결정)")).isEqualTo(1)

        // 신청만 알고 결정을 몰랐다 — 결정이 더 중대한데 빠져 있었다
        assertThat(tierOf("회생절차개시결정")).isEqualTo(3)
        assertThat(tierOf("회생절차개시신청")).isEqualTo(3)

        // 제기·신청만 알고 판결·결정을 몰랐다
        assertThat(tierOf("소송등의판결ㆍ결정")).isEqualTo(3)
        assertThat(tierOf("소송등의판결ㆍ결정(일정금액이상의청구)")).isEqualTo(3)
    }

    @Test
    fun `빠져 있던 카테고리를 넣는다`() {
        assertThat(tierOf("공개매수신고서")).isEqualTo(1)
        assertThat(tierOf("공개매수설명서")).isEqualTo(1)
        // T2가 `매출액또는손익구조`만 봐서 잠정실적이 통째로 빠져 있었다
        assertThat(tierOf("영업(잠정)실적(공정공시)")).isEqualTo(2)
        // 우발채무 — 실측 83건으로 걸러진 것 중 손에 꼽는 규모다
        assertThat(tierOf("타인에대한채무보증결정")).isEqualTo(3)
        assertThat(tierOf("타인에대한채무보증결정(자회사의 주요경영사항)")).isEqualTo(3)
        assertThat(tierOf("타인에대한담보제공결정")).isEqualTo(3)
        assertThat(tierOf("파생상품거래손실발생")).isEqualTo(3)
        assertThat(tierOf("투자유의안내")).isEqualTo(3)
        // T3에 `조회공시요구`는 있는데 그 답이 아닌 자발적 해명은 빠져 있었다
        assertThat(tierOf("풍문또는보도에대한해명(미확정)")).isEqualTo(3)
    }

    @Test
    fun `Tier 6은 지분공시다 — 정보 가치는 있고 주가 직결은 아니다`() {
        assertThat(tierOf("주식등의대량보유상황보고서(일반)")).isEqualTo(6)
        assertThat(tierOf("주식등의대량보유상황보고서(약식)")).isEqualTo(6)
        assertThat(tierOf("최대주주등소유주식변동신고서")).isEqualTo(6)
        // 사전공시제도 — 임원이 팔 계획을 미리 알린다. 사후 보고서(T4)와 다른 서류다
        assertThat(tierOf("임원ㆍ주요주주특정증권등거래계획보고서")).isEqualTo(6)
        assertThat(tierOf("임원ㆍ주요주주특정증권등거래계획철회보고서")).isEqualTo(6)
    }

    @Test
    fun `거래계획보고서가 elestock 트리거를 훔치면 안 된다`() {
        // 🔴 T4는 단순 분류가 아니라 elestock 호출 대상을 가리는 게이트다(TIER_INSIDER).
        // 거래계획보고서를 T4에 넣었으면 elestock에 없는 건으로 호출이 나갔을 것이다.
        assertThat(tierOf("임원ㆍ주요주주특정증권등소유상황보고서")).isEqualTo(DartWhitelist.TIER_INSIDER)
        assertThat(tierOf("임원ㆍ주요주주특정증권등거래계획보고서"))
            .isNotEqualTo(DartWhitelist.TIER_INSIDER)
    }

    @Test
    fun `최대주주변경과 최대주주등소유주식변동신고서는 다른 사건이다`() {
        // 앞은 지배주주가 바뀐 것(T1), 뒤는 지분만 움직인 것(T6). 키워드가 서로를 삼키면 안 된다.
        assertThat(tierOf("최대주주변경")).isEqualTo(1)
        assertThat(tierOf("최대주주등소유주식변동신고서")).isEqualTo(6)
    }

    @Test
    fun `튜닝 뒤에도 걸러낼 것은 걸러낸다`() {
        // 실측 미적중 상위. 넣은 키워드가 이웃까지 빨아들이지 않았는지 본다.
        assertThat(tierOf("대규모기업집단현황공시[분기별공시(개별회사용)]")).isNull()
        assertThat(tierOf("투자설명서(일괄신고)")).isNull()
        assertThat(tierOf("일괄신고추가서류(파생결합사채-주가연계파생결합사채)")).isNull()
        assertThat(tierOf("주주명부폐쇄기간또는기준일설정")).isNull()
        assertThat(tierOf("의결권대리행사권유참고서류")).isNull()
        assertThat(tierOf("증권신고서(지분증권)")).isNull()
    }
}
