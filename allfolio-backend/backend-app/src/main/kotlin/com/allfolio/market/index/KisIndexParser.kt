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
 * 다만 절댓값은 **모순도 같이 지운다**. 원본 부호가 `prdy_vrss_sign`과 어긋나면
 * (예: 값 `-233.51` + 부호 `2`) 그건 우리가 필드 뜻을 잘못 알고 있다는 뜻이므로 거부한다.
 * 놓치면 하락한 날을 상승으로 저장한다. 거부만 하고 원본 부호로 방향을 추론하지는 않는다 —
 * 자세한 규칙은 [magnitude].
 *
 * 응답의 모든 값은 **문자열**이다.
 */
@Component
class KisIndexParser {

    fun parse(indexCode: String, output: Map<String, Any?>): IndexQuote {
        val price = number(output, "bstp_nmix_prpr")
        val sign = text(output, "prdy_vrss_sign")
        val direction = direction(sign)
        val rawChange = magnitude(output, "bstp_nmix_prdy_vrss", sign, direction)
        val rawRate = magnitude(output, "bstp_nmix_prdy_ctrt", sign, direction)

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

    /**
     * 크기만 돌려준다. 방향은 여전히 `prdy_vrss_sign`에서만 온다 — **여기서 원본 부호로
     * 방향을 추론하지 않는다.** 다만 `abs()`는 크기를 남기면서 *모순을 지운다*: KIS가
     * `bstp_nmix_prdy_vrss: "-233.51"`을 `prdy_vrss_sign: "2"`(상승)와 함께 보내도
     * 파서는 +233.51을 내놓고 아무도 눈치채지 못한다. [IndexGuards]는 구조적으로 이걸 못 잡는다 —
     * 같은 방향을 `change`와 `changeRate`에 똑같이 곱하므로 둘은 항상 서로 맞는다.
     *
     * KIS가 부호 있는 관례를 쓰는지 없는 관례를 쓰는지는 실측으로 확정되지 않았으므로,
     * **어느 쪽도 가정하지 않고 모순만** 거부한다.
     * - 값이 음수인데 코드가 상승(1·2)이나 보합(3) → 모순, 거부
     * - 값이 음수이고 코드가 하락(4·5) → 부호 있는 관례로 일관, 통과
     * - 값이 0 이상 → 어느 코드와도 모순이 아니다(부호 없는 관례), 통과
     */
    private fun magnitude(
        output: Map<String, Any?>,
        key: String,
        sign: String,
        direction: Int,
    ): BigDecimal {
        val raw = number(output, key)
        if (raw.signum() < 0 && direction >= 0) {
            throw KisIndexException(
                "KIS 지수 응답의 부호가 서로 모순됩니다: $key='${raw.toPlainString()}'(음수)인데 " +
                    "prdy_vrss_sign='$sign'(${if (direction == 0) "보합" else "상승"})입니다",
            )
        }
        return raw.abs()
    }

    private fun text(output: Map<String, Any?>, key: String): String =
        output[key]?.toString()?.trim()
            ?: throw KisIndexException("KIS 지수 응답에 $key 가 없습니다")

    private fun number(output: Map<String, Any?>, key: String): BigDecimal =
        text(output, key).toBigDecimalOrNull()
            ?: throw KisIndexException("KIS 지수 응답의 $key 가 숫자가 아닙니다: '${output[key]}'")
}
