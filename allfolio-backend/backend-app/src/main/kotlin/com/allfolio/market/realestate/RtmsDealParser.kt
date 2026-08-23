package com.allfolio.market.realestate

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate

/** @param deals 파싱된 거래. @param skipped 값이 이상해 버린 행 수 */
data class RtmsFetch(val deals: List<RtmsDeal>, val skipped: Int, val totalCount: Int)

/**
 * 국토부 아파트 매매 실거래가 **상세** 자료 응답 파서.
 *
 * ## 형식은 추정이 아니라 실측이다
 *
 * 2026-08-21, 종로·강남·분당 × 2026-05~07 **2,660건**으로 확정했다. 아래 다섯은 전부
 * 문서만 보고 짰으면 깨졌을 것들이고, 그래서 테스트에 실제 응답을 그대로 박아 뒀다.
 *
 * | 항목 | 실제 |
 * |---|---|
 * | `dealAmount` | **콤마 문자열 · 만원 단위** — `"55,000"` = 5.5억 (391/391이 콤마 포함) |
 * | `excluUseAr` | **int와 float가 섞인다** — `60`(8건) · `84.93`(383건) |
 * | `cdealType` | **해제 거래 표시** — `' '`=정상 386건 · `'O'`=해제 5건 |
 * | `cdealDay` | 해제일이 **두 자리 연도** — `'26.07.13'` |
 * | 0건인 달 | `items`가 **빈 문자열 `""`** (`{}`도 `null`도 아니다) |
 *
 * **타입이 섞이는 필드가 더 있다**(`aptDong`·`bonbun`·`jibun`·`roadNmbCd`가 int/str 혼재).
 * 이 API는 타입 일관성이 없으므로 **엄격한 파서를 쓰면 안 된다** — 필요한 필드만
 * 관대하게 읽는다.
 *
 * ## 한 행이 이상해도 나머지를 버리지 않는다
 *
 * 450건짜리 응답에서 한 행의 금액이 깨졌다고 449건을 버리면, 그 달 시세가 통째로 사라진다.
 * 버린 행은 [RtmsFetch.skipped]로 세어 올려 수집 요약에 남긴다.
 */
object RtmsDealParser {

    private val logger = LoggerFactory.getLogger(RtmsDealParser::class.java)

    /** 응답 금액 단위. `"55,000"` → 5.5억 */
    private val MAN_WON = BigDecimal(10_000)

    /** 해제 거래 표시. 공백이면 정상 거래다 */
    private const val CANCELLED_MARK = "O"

    /** 정상 응답의 결과 코드 */
    private const val OK = "000"

    /**
     * @throws RtmsApiException 헤더가 정상이 아닐 때. **행 단위 실패와 구분한다** —
     *         이쪽은 그 (시군구, 월)을 통째로 못 받은 것이라 재시도 대상이다.
     */
    fun parse(root: JsonNode): RtmsFetch {
        val response = root.path("response")
        val code = response.path("header").path("resultCode").asText("")
        if (code != OK) {
            val msg = response.path("header").path("resultMsg").asText("")
            // 인증키·승인 문제가 여기로 온다. 키 값은 절대 싣지 않는다 — 이 메시지는
            // 수집 요약을 타고 어드민 응답과 Actions 주석까지 나간다.
            throw RtmsApiException("실거래가 API 오류 resultCode=$code resultMsg=$msg")
        }

        val body = response.path("body")
        val totalCount = body.path("totalCount").asInt(0)

        // 거래 0건인 달은 items가 빈 문자열이다. `.path("item")`은 조용히 missing을 주므로
        // 여기서 걸러 두지 않으면 "0건"과 "형식이 바뀌었다"가 구분되지 않는다.
        val items = body.path("items")
        if (items.isMissingNode || items.isNull || (items.isTextual && items.asText().isBlank())) {
            return RtmsFetch(emptyList(), skipped = 0, totalCount = totalCount)
        }

        val item = items.path("item")
        // 1건일 때 객체 하나로 올 수 있다 — 공공데이터포털 계열의 흔한 형태다.
        val nodes = when {
            item.isArray -> item.toList()
            item.isObject -> listOf(item)
            else -> emptyList()
        }

        var skipped = 0
        val deals = nodes.mapNotNull { node ->
            runCatching { parseRow(node) }.getOrElse {
                skipped++
                logger.warn("[실거래가] 행 파싱 실패 aptSeq={} 사유={}",
                    node.path("aptSeq").asText("?"), it.message)
                null
            }
        }
        return RtmsFetch(deals, skipped, totalCount)
    }

    private fun parseRow(n: JsonNode): RtmsDeal {
        val cdealType = n.path("cdealType").asText("").trim()
        return RtmsDeal(
            aptSeq = n.path("aptSeq").asText("").trim().ifBlank { error("aptSeq 없음") },
            aptName = n.path("aptNm").asText("").trim(),
            exclusiveAreaM2 = area(n.path("excluUseAr")),
            dealDate = LocalDate.of(
                n.path("dealYear").asInt(), n.path("dealMonth").asInt(), n.path("dealDay").asInt(),
            ),
            dealAmountKrw = amountKrw(n.path("dealAmount").asText("")),
            floor = n.path("floor").asInt(),
            buildYear = n.path("buildYear").asInt(0).takeIf { it > 0 },
            sggCode = n.path("sggCd").asText("").trim(),
            umdName = n.path("umdNm").asText("").trim(),
            cancelled = cdealType.equals(CANCELLED_MARK, ignoreCase = true),
            cancelledOn = cancelledDate(n.path("cdealDay").asText("")),
        )
    }

    /**
     * `"55,000"`(만원) → `550_000_000`(원).
     *
     * **`asLong()`을 쓰면 안 된다** — 콤마가 있으면 Jackson이 0을 준다. 조용히 0원짜리
     * 거래가 중앙값에 들어간다.
     */
    private fun amountKrw(raw: String): Long {
        val digits = raw.replace(",", "").trim()
        require(digits.isNotEmpty()) { "dealAmount 비어 있음" }
        val manWon = digits.toLongOrNull() ?: error("dealAmount 형식 이상: $raw")
        require(manWon > 0) { "dealAmount가 0 이하: $raw" }
        return BigDecimal(manWon).multiply(MAN_WON).toLong()
    }

    /**
     * 전용면적. **int와 float가 섞여 온다** — `60`과 `84.93`.
     *
     * `asDouble()`을 거치지 않고 텍스트에서 `BigDecimal`을 만든다. 매칭이 정확 일치라
     * 이진 부동소수 오차가 들어가면 `84.93`이 안 맞을 수 있다.
     */
    private fun area(n: JsonNode): BigDecimal {
        val raw = n.asText("").trim()
        require(raw.isNotEmpty()) { "excluUseAr 비어 있음" }
        val v = raw.toBigDecimalOrNull() ?: error("excluUseAr 형식 이상: $raw")
        require(v > BigDecimal.ZERO) { "excluUseAr가 0 이하: $raw" }
        return v
    }

    /**
     * 해제일 `"26.07.13"` → `2026-07-13`. 공백이면 null.
     *
     * **두 자리 연도다.** 2000을 더한다 — 이 API에 1900년대 해제일은 없다(실거래가 신고
     * 제도가 2006년 시작이다).
     */
    private fun cancelledDate(raw: String): LocalDate? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val parts = t.split(".")
        if (parts.size != 3) {
            logger.warn("[실거래가] cdealDay 형식 이상: {}", t)
            return null
        }
        return runCatching {
            LocalDate.of(2000 + parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }.getOrElse {
            logger.warn("[실거래가] cdealDay 파싱 실패: {}", t)
            null
        }
    }
}

/** (시군구, 월) 한 묶음을 통째로 못 받았을 때. 행 단위 실패와 구분한다 */
class RtmsApiException(message: String) : RuntimeException(message)
