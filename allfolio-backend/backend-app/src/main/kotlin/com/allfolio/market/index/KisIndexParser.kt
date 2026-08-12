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
 * **부호를 값에서 읽지 않는다.** 실측 응답(2026-08-12)은 상승일이라
 * `bstp_nmix_prdy_vrss`가 양수로 왔는데, 하락일에 마이너스가 붙을지는 알 수 없다 —
 * 같은 응답의 `dryy_lwpr_vrss_prpr_rate`가 `"-56.02"`인 걸 보면 KIS는 어떤 필드엔
 * 부호를 싣는다. 값은 절댓값으로만 쓰고 방향은 `prdy_vrss_sign`에서만 가져오면
 * 원본에 부호가 있든 없든 결과가 같다.
 *
 * 응답의 모든 값은 **문자열**이다.
 */
@Component
class KisIndexParser {

    fun parse(indexCode: String, output: Map<String, Any?>): IndexQuote {
        val price = number(output, "bstp_nmix_prpr")
        val rawChange = number(output, "bstp_nmix_prdy_vrss").abs()
        val rawRate = number(output, "bstp_nmix_prdy_ctrt").abs()
        val direction = direction(text(output, "prdy_vrss_sign"))

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
     * KIS 관례: 1=상한 2=상승 3=보합 4=하한 5=하락.
     * **실측으로 확인된 것은 2뿐이다.** 그 밖의 값은 거부한다 —
     * 기본값을 상승으로 두면 모르는 코드가 왔을 때 하락을 상승으로 저장한다.
     */
    private fun direction(sign: String): Int = when (sign) {
        "1", "2" -> 1
        "3" -> 0
        "4", "5" -> -1
        else -> throw KisIndexException("알 수 없는 전일대비 부호 코드: '$sign'")
    }

    private fun text(output: Map<String, Any?>, key: String): String =
        output[key]?.toString()?.trim()
            ?: throw KisIndexException("KIS 지수 응답에 $key 가 없습니다")

    private fun number(output: Map<String, Any?>, key: String): BigDecimal =
        text(output, key).toBigDecimalOrNull()
            ?: throw KisIndexException("KIS 지수 응답의 $key 가 숫자가 아닙니다: '${output[key]}'")
}
