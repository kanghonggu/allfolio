package com.allfolio.market.realestate

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 국토부 실거래가 파서.
 *
 * **아래 JSON은 지어낸 게 아니라 2026-08-21 운영 API가 실제로 준 응답이다.**
 * 문서만 보고 짰으면 다섯 군데가 깨졌을 것이라(콤마 금액·혼합 타입 면적·해제 거래·
 * 두 자리 연도·빈 문자열 items) 실측을 그대로 박아 둔다.
 *
 * **이 값들을 "정리"하지 말 것** — 형식이 바뀌면 화면이 아니라 여기가 먼저 깨져야 한다.
 */
class RtmsDealParserTest {

    private val mapper = ObjectMapper()

    private fun parse(json: String) = RtmsDealParser.parse(mapper.readTree(json))

    /** 2026-08-21 `LAWD_CD=11110&DEAL_YMD=202607` 응답의 첫 행 (필드 생략 없음) */
    private val 실측_한건 = """
    {"response":{"header":{"resultCode":"000","resultMsg":"OK"},"body":{"items":{"item":[
      {"aptDong":" ","aptNm":"동문(482-0)","aptSeq":"11110-132","bonbun":"0482","bubun":"0000",
       "buildYear":2002,"buyerGbn":"개인","cdealDay":" ","cdealType":" ","dealAmount":"55,000",
       "dealDay":23,"dealMonth":7,"dealYear":2026,"dealingGbn":"중개거래",
       "estateAgentSggNm":"서울 성북구","excluUseAr":60,"floor":5,"jibun":482,"landCd":1,
       "landLeaseholdGbn":"N","rgstDate":" ","roadNm":"숭인동길","roadNmBonbun":"00071",
       "roadNmBubun":"00000","roadNmCd":4100204,"roadNmSeq":"01","roadNmSggCd":11110,
       "roadNmbCd":0,"sggCd":11110,"slerGbn":"개인","umdCd":17500,"umdNm":"숭인동"}
    ]},"numOfRows":200,"pageNo":1,"totalCount":37}}}
    """

    @Test
    fun `실측 한 건을 그대로 읽는다`() {
        val d = parse(실측_한건).deals.single()

        assertThat(d.aptSeq).isEqualTo("11110-132")
        assertThat(d.aptName).isEqualTo("동문(482-0)")
        assertThat(d.dealDate).isEqualTo(LocalDate.of(2026, 7, 23))
        assertThat(d.floor).isEqualTo(5)
        assertThat(d.buildYear).isEqualTo(2002)
        assertThat(d.sggCode).isEqualTo("11110")
        assertThat(d.umdName).isEqualTo("숭인동")
        assertThat(d.cancelled).isFalse()
        assertThat(d.cancelledOn).isNull()
    }

    /**
     * **`"55,000"`은 5.5억이다.** 만원 단위 콤마 문자열이라 그냥 숫자로 읽으면 0이 된다 —
     * 0원짜리 거래가 조용히 중앙값에 들어간다. 실측 391건 전부 콤마를 포함한다.
     */
    @Test
    fun `거래금액은 만원 단위 콤마 문자열이다`() {
        assertThat(parse(실측_한건).deals.single().dealAmountKrw).isEqualTo(550_000_000L)
    }

    /** 억 단위가 커도 정확해야 한다 — `"116,000"` = 11.6억 */
    @Test
    fun `큰 금액도 정확히 환산한다`() {
        val d = parse(실측_한건.replace(""""dealAmount":"55,000"""", """"dealAmount":"116,000"""")).deals.single()
        assertThat(d.dealAmountKrw).isEqualTo(1_160_000_000L)
    }

    /**
     * **전용면적이 int와 float로 섞여 온다** — 실측 391건 중 int 8 · float 383.
     * 한쪽만 가정하면 나머지가 통째로 버려진다.
     */
    @Test
    fun `전용면적은 정수와 소수가 섞여 온다`() {
        val 정수 = parse(실측_한건).deals.single()
        assertThat(정수.exclusiveAreaM2).isEqualByComparingTo("60")

        val 소수 = parse(실측_한건.replace(""""excluUseAr":60""", """"excluUseAr":84.93""")).deals.single()
        assertThat(소수.exclusiveAreaM2).isEqualByComparingTo("84.93")
    }

    /**
     * **부동소수를 거치지 않는다.** 매칭이 정확 일치라, 같은 단지 안에 `84.83`과 `84.86`이
     * 함께 있는 상황(실측)에서 이진 오차가 끼면 엉뚱한 평형을 잡는다.
     */
    @Test
    fun `전용면적을 문자열에서 그대로 만든다`() {
        val d = parse(실측_한건.replace(""""excluUseAr":60""", """"excluUseAr":84.83""")).deals.single()

        assertThat(d.exclusiveAreaM2.toPlainString()).isEqualTo("84.83")
        assertThat(d.exclusiveAreaM2).isNotEqualByComparingTo("84.86")
    }

    // ── 해제(취소) 거래 ────────────────────────────────────────────────────

    /**
     * **실측 재현.** 391건 중 5건이 해제였다(`cdealType='O'`, `cdealDay='26.07.13'`).
     * 이걸 중앙값에 넣으면 성사되지 않은 가격이 시세가 된다.
     */
    @Test
    fun `해제 거래를 표시한다`() {
        val json = 실측_한건
            .replace(""""cdealDay":" ","cdealType":" """", """"cdealDay":"26.07.13","cdealType":"O"""")

        val d = parse(json).deals.single()

        assertThat(d.cancelled).isTrue()
        assertThat(d.cancelledOn).isEqualTo(LocalDate.of(2026, 7, 13))
    }

    /** 해제일이 **두 자리 연도**다 — 4자리로 읽으면 26년이 된다 */
    @Test
    fun `해제일은 두 자리 연도다`() {
        val json = 실측_한건
            .replace(""""cdealDay":" ","cdealType":" """", """"cdealDay":"26.08.15","cdealType":"O"""")

        assertThat(parse(json).deals.single().cancelledOn).isEqualTo(LocalDate.of(2026, 8, 15))
    }

    /** 공백은 해제가 아니다 — 실측 386/391이 공백이다 */
    @Test
    fun `공백이면 정상 거래다`() {
        val d = parse(실측_한건).deals.single()

        assertThat(d.cancelled).isFalse()
        assertThat(d.cancelledOn).isNull()
    }

    /** 해제 거래도 **버리지 않고 표시만 한다** — 캐시가 상태 변화를 덮어써야 하기 때문이다 */
    @Test
    fun `해제 거래도 목록에는 남는다`() {
        val json = 실측_한건
            .replace(""""cdealDay":" ","cdealType":" """", """"cdealDay":"26.07.13","cdealType":"O"""")

        assertThat(parse(json).deals).hasSize(1)
    }

    // ── 빈 응답·형식 예외 ──────────────────────────────────────────────────

    /**
     * **거래 0건인 달은 `items`가 빈 문자열이다.** `{}`도 `null`도 아니다 —
     * 실측(`LAWD_CD=11110&DEAL_YMD=199501`)에서 확인했다.
     */
    @Test
    fun `거래가 없는 달은 빈 문자열로 온다`() {
        val json = """
        {"response":{"header":{"resultCode":"000","resultMsg":"OK"},
         "body":{"items":"","numOfRows":200,"pageNo":1,"totalCount":0}}}
        """

        val fetch = parse(json)

        assertThat(fetch.deals).isEmpty()
        assertThat(fetch.skipped).isZero()
        assertThat(fetch.totalCount).isZero()
    }

    /** 1건일 때 배열이 아니라 객체 하나로 올 수 있다 — 포털 계열의 흔한 형태다 */
    @Test
    fun `한 건이면 객체로 와도 읽는다`() {
        val json = 실측_한건.replace(""""item":[""", """"item":""").replace("""}
    ]},"numOfRows"""", """}},"numOfRows"""")

        assertThat(parse(json).deals).hasSize(1)
    }

    /** `totalCount`가 페이징 판단에 쓰인다 — 실측 분당 2026-07이 450건이었다 */
    @Test
    fun `totalCount를 함께 준다`() {
        assertThat(parse(실측_한건).totalCount).isEqualTo(37)
    }

    // ── 한 행이 이상해도 나머지를 살린다 ───────────────────────────────────

    /**
     * **450건 중 한 행이 깨졌다고 449건을 버리면 그 달 시세가 통째로 사라진다.**
     */
    @Test
    fun `깨진 행만 버리고 나머지는 살린다`() {
        val json = """
        {"response":{"header":{"resultCode":"000","resultMsg":"OK"},"body":{"items":{"item":[
          {"aptSeq":"11110-132","aptNm":"동문","excluUseAr":60,"dealAmount":"55,000",
           "dealYear":2026,"dealMonth":7,"dealDay":23,"floor":5,"buildYear":2002,
           "sggCd":11110,"umdNm":"숭인동","cdealType":" ","cdealDay":" "},
          {"aptSeq":"11110-999","aptNm":"깨진행","excluUseAr":84.5,"dealAmount":"",
           "dealYear":2026,"dealMonth":7,"dealDay":24,"floor":3,"buildYear":2010,
           "sggCd":11110,"umdNm":"숭인동","cdealType":" ","cdealDay":" "}
        ]},"totalCount":2}}}
        """

        val fetch = parse(json)

        assertThat(fetch.deals).hasSize(1)
        assertThat(fetch.deals.single().aptSeq).isEqualTo("11110-132")
        assertThat(fetch.skipped).isEqualTo(1)
    }

    @Test
    fun `금액이 0이면 그 행을 버린다`() {
        val json = 실측_한건.replace(""""dealAmount":"55,000"""", """"dealAmount":"0"""")

        val fetch = parse(json)

        assertThat(fetch.deals).isEmpty()
        assertThat(fetch.skipped).isEqualTo(1)
    }

    // ── 헤더 오류는 행 오류와 다르다 ───────────────────────────────────────

    /**
     * **(시군구, 월)을 통째로 못 받은 것**이라 재시도 대상이다. 행 단위 실패처럼
     * 조용히 0건으로 넘기면 "그 달은 거래가 없었다"로 굳는다.
     */
    @Test
    fun `헤더가 정상이 아니면 예외로 알린다`() {
        val json = """
        {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}}
        """

        assertThatThrownBy { parse(json) }
            .isInstanceOf(RtmsApiException::class.java)
            .hasMessageContaining("SERVICE_KEY_IS_NOT_REGISTERED_ERROR")
    }

    /** 인증키가 메시지에 실리면 안 된다 — 이 문자열은 어드민 응답까지 나간다 */
    @Test
    fun `오류 메시지에 인증키를 싣지 않는다`() {
        val json = """
        {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}}
        """

        val message = runCatching { parse(json) }.exceptionOrNull()!!.message!!

        assertThat(message).doesNotContain("serviceKey")
        assertThat(message).doesNotContain("http")
    }
}
