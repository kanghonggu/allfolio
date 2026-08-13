package com.allfolio.market.index

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 해외 지수 봉 하나. 저장 키·시장 상태는 수집 서비스가 정한다. */
data class OverseasIndexBar(
    val quote: IndexQuote,
    /** `output2[0].stck_bsop_date` — 시계로 유추하지 않는다 */
    val tradeDate: LocalDate,
    /** `output2[1].stck_bsop_date`. 봉이 하나뿐이면 null */
    val prevCloseDate: LocalDate?,
    /** 응답이 준 `ovrs_nmix_prdy_clpr`. 가드가 역산값과 대조한다 */
    val reportedPrevClose: BigDecimal,
    /** `hts_kor_isnm` — 설정의 `nameContains`와 대조해 코드 오선택을 잡는다 */
    val nameFromKis: String,
)

/**
 * KIS 해외 지수 일별 시세 응답 → 도메인 (AF-110).
 *
 * 응답은 두 갈래다. `output1`은 요약(현재가·전일대비·부호·이름), `output2`는 일봉 배열이고
 * **최신 봉이 먼저**(내림차순) 온다. 실측 원문은 [KisIndexClient.fetchOverseasRaw]로 받아
 * 2026-08-13 운영에서 확인했다 — 그날이 하락일이라 부호 규약까지 드러났다.
 *
 * **국내 파서와 다른 점 셋:**
 * 1. **거래일이 응답에 들어 있다.** 국내는 조회 시각으로 거래일을 정하지만, 해외는 시계로 유추하면
 *    한국 시각 기준으로 하루가 밀린다. `output2[0].stck_bsop_date`를 그대로 쓴다.
 * 2. **등락률 필드명이 `prdy_ctrt`다** — 국내의 `bstp_nmix_prdy_ctrt`가 아니다. 국내 이름을
 *    그대로 옮겨 오면 필드가 없다며 통째로 실패한다.
 * 3. **응답이 전일종가를 직접 준다**(`ovrs_nmix_prdy_clpr`). 그래도 [IndexQuote.prevClose]는
 *    역산값을 쓴다 — 아래 [parse] 참조.
 *
 * 부호 규칙은 [IndexSignRule]을 그대로 쓴다. 필드명만 다를 뿐 규약이 같으므로 여기에 따로
 * 구현하지 않는다.
 *
 * 응답의 모든 값은 **문자열**이다.
 */
@Component
class KisOverseasIndexParser {

    /**
     * @param indexCode 우리가 정한 canonical 코드. 오류 메시지에도 이게 실려야
     *                  운영자가 9종 중 어느 지수가 깨졌는지 안다
     * @param body [KisIndexClient.fetchOverseasRaw]가 돌려준 응답 **전체**(`output1`+`output2`)
     */
    fun parse(indexCode: String, body: Map<String, Any?>): OverseasIndexBar {
        val summary = summary(indexCode, body)
        val bars = bars(indexCode, body)

        // 현재가는 output1.ovrs_nmix_prpr에도 같은 값으로 들어 있지만 **일부러 봉에서 읽는다.**
        // 거래일과 값이 같은 행에서 와야 짝이 어긋날 수 없다. output1은 조회 시점의 요약이라
        // 장중 조회나 KIS 쪽 갱신 시차에서 최신 봉과 갈라질 여지가 있고, 그러면 20260813 자로
        // 다른 날 가격이 저장돼도 아무 데서도 티가 나지 않는다.
        val price = number(indexCode, bars[0], "ovrs_nmix_prpr")
        val tradeDate = date(indexCode, bars[0], "stck_bsop_date")
        // 봉이 하나뿐이면(상장 직후·긴 연휴) 전일이 언제인지 우리는 모른다. 거래일에서 하루를
        // 빼는 식으로 지어내면 존재하지 않는 거래일을 화면이 주장하게 된다.
        val prevCloseDate = bars.getOrNull(1)?.let { date(indexCode, it, "stck_bsop_date") }

        val sign = text(indexCode, summary, "prdy_vrss_sign")
        val direction = IndexSignRule.direction(sign)
        val rawChange = magnitude(indexCode, summary, "ovrs_nmix_prdy_vrss", sign, direction)
        val rawRate = magnitude(indexCode, summary, "prdy_ctrt", sign, direction)

        val change = rawChange.multiply(BigDecimal(direction))
        return OverseasIndexBar(
            quote = IndexQuote(
                indexCode = indexCode,
                price = price,
                // 응답이 준 ovrs_nmix_prdy_clpr을 여기 넣지 말 것. 그 값은 reportedPrevClose에
                // 따로 담아, Task 3의 가드가 "역산값 vs 응답값"으로 대조한다. 여기에 응답값을
                // 그대로 쓰면 두 값이 언제나 같아져 그 교차검증이 자기 자신과의 비교가 된다.
                prevClose = price.subtract(change),
                change = change,
                changeRate = rawRate.multiply(BigDecimal(direction)),
            ),
            tradeDate = tradeDate,
            prevCloseDate = prevCloseDate,
            reportedPrevClose = number(indexCode, summary, "ovrs_nmix_prdy_clpr"),
            nameFromKis = text(indexCode, summary, "hts_kor_isnm"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun summary(indexCode: String, body: Map<String, Any?>): Map<String, Any?> =
        body["output1"] as? Map<String, Any?>
            ?: throw KisIndexException("KIS 해외 지수 응답에 output1이 없습니다 code=$indexCode: ${body["msg1"]}")

    /**
     * 일봉 배열. **최신 봉이 먼저**다 — 정렬하지 않고 그 순서를 그대로 믿는다(실측 확인).
     * 비어 있으면 거래일도 현재가도 없으므로 거부한다. 조회 구간에 휴장만 들어 있으면 이렇게 온다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun bars(indexCode: String, body: Map<String, Any?>): List<Map<String, Any?>> {
        val rows = body["output2"] as? List<*>
            ?: throw KisIndexException("KIS 해외 지수 응답에 output2가 없습니다 code=$indexCode: ${body["msg1"]}")
        if (rows.isEmpty()) {
            throw KisIndexException("KIS 해외 지수 응답의 output2가 비어 있습니다 code=$indexCode")
        }
        // 원소까지 확인한다. 제네릭은 지워지므로 `as? List<Map<..>>`는 어떤 List든 통과시키고,
        // 원소가 Map이 아니면 한참 뒤 필드를 꺼낼 때 ClassCastException이 raw로 샌다 —
        // 이 패키지의 계약은 "응답이 이상하면 KisIndexException"이다.
        return rows.map {
            it as? Map<String, Any?>
                ?: throw KisIndexException("KIS 해외 지수 응답의 output2 원소가 객체가 아닙니다 code=$indexCode: '$it'")
        }
    }

    private fun magnitude(
        indexCode: String,
        output: Map<String, Any?>,
        key: String,
        sign: String,
        direction: Int,
    ): BigDecimal = IndexSignRule.magnitude(number(indexCode, output, key), key, sign, direction)

    private fun text(indexCode: String, output: Map<String, Any?>, key: String): String =
        output[key]?.toString()?.trim()
            ?: throw KisIndexException("KIS 해외 지수 응답에 $key 가 없습니다 code=$indexCode")

    private fun number(indexCode: String, output: Map<String, Any?>, key: String): BigDecimal =
        text(indexCode, output, key).toBigDecimalOrNull()
            ?: throw KisIndexException("KIS 해외 지수 응답의 $key 가 숫자가 아닙니다 code=$indexCode: '${output[key]}'")

    /** `yyyyMMdd`. 형식이 바뀌면 조용히 오늘로 떨어뜨리지 말고 거부한다 — 거래일은 저장 키다 */
    private fun date(indexCode: String, output: Map<String, Any?>, key: String): LocalDate {
        val raw = text(indexCode, output, key)
        return try {
            LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: java.time.format.DateTimeParseException) {
            throw KisIndexException("KIS 해외 지수 응답의 $key 가 날짜가 아닙니다 code=$indexCode: '$raw' (${e.message})")
        }
    }
}
