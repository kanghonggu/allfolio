package com.allfolio.market.index

import org.springframework.stereotype.Component
import java.math.BigDecimal

/** 파싱된 지수 시세 한 건. 저장 키(거래일·슬롯)는 수집 서비스가 정한다. */
data class IndexQuote(
    val indexCode: String,
    val price: BigDecimal,
    val prevClose: BigDecimal,
    val change: BigDecimal,
    val changeRate: BigDecimal,
)

/**
 * KIS 업종 지수 응답 → 도메인 (AF-101).
 *
 * **부호를 값에서 읽지 않는다.** 국내 실측 응답(2026-08-12)은 상승일이라
 * `bstp_nmix_prdy_vrss`가 양수로 왔고, **국내가 하락일에 마이너스를 붙이는지는 아직 모른다** —
 * 같은 응답의 `dryy_lwpr_vrss_prpr_rate`가 `"-56.02"`인 걸 보면 KIS는 어떤 필드엔
 * 부호를 싣는다. (해외 응답은 AF-110의 하락일 실측에서 부호 있는 관례로 **확인됐다** —
 * [IndexSignRule].) 값은 절댓값으로만 쓰고 방향은 `prdy_vrss_sign`에서만 가져오면
 * 원본에 부호가 있든 없든 결과가 같다.
 *
 * 부호 규칙 자체는 [IndexSignRule]에 있다 — 필드명만 다를 뿐 해외 파서와 같은 규약이라
 * 여기 복사해 두면 한쪽만 고치는 날이 온다.
 *
 * 응답의 모든 값은 **문자열**이다.
 */
@Component
class KisIndexParser {

    fun parse(indexCode: String, output: Map<String, Any?>): IndexQuote {
        val price = number(output, "bstp_nmix_prpr")
        val sign = text(output, "prdy_vrss_sign")
        val direction = IndexSignRule.direction(sign)
        val rawChange = magnitude(output, "bstp_nmix_prdy_vrss", sign)
        val rawRate = magnitude(output, "bstp_nmix_prdy_ctrt", sign)

        val change = rawChange.multiply(BigDecimal(direction))
        return IndexQuote(
            indexCode = indexCode,
            price = price,
            prevClose = price.subtract(change),
            change = change,
            changeRate = rawRate.multiply(BigDecimal(direction)),
        )
    }

    /**
     * 값을 꺼내 [IndexSignRule.magnitude]에 넘기기만 한다. 규칙은 저기 하나뿐이다.
     *
     * 이 한 겹을 없애고 호출부에서 `IndexSignRule.magnitude(number(output, key), key, ...)`를
     * 직접 부르면 **필드명 문자열을 두 번 쓰게 된다** — 읽은 필드와 오류 메시지가 가리키는 필드가
     * 어긋나도 아무도 모른다.
     */
    private fun magnitude(
        output: Map<String, Any?>,
        key: String,
        sign: String,
    ): BigDecimal = IndexSignRule.magnitude(number(output, key), key, sign)

    private fun text(output: Map<String, Any?>, key: String): String =
        output[key]?.toString()?.trim()
            ?: throw KisIndexException("KIS 지수 응답에 $key 가 없습니다")

    private fun number(output: Map<String, Any?>, key: String): BigDecimal =
        text(output, key).toBigDecimalOrNull()
            ?: throw KisIndexException("KIS 지수 응답의 $key 가 숫자가 아닙니다: '${output[key]}'")
}
