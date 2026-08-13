package com.allfolio.market.index

import java.math.BigDecimal

/**
 * KIS 지수 응답의 전일대비 부호 규칙 (AF-101에서 세우고 AF-110에서 실측 검증).
 *
 * **부호를 값에서 읽지 않는다.** 값은 절댓값으로만 쓰고 방향은 `prdy_vrss_sign`에서만
 * 가져오면, 원본에 부호가 있든 없든 결과가 같다.
 *
 * 국내·해외 응답이 필드명은 다르지만(`bstp_nmix_prdy_vrss` vs `ovrs_nmix_prdy_vrss`,
 * `bstp_nmix_prdy_ctrt` vs `prdy_ctrt`) **부호 규약은 같다.** 그래서 [KisIndexParser]와
 * [KisOverseasIndexParser]가 이 하나를 공유한다 — 각자 복사해 두면 한쪽만 고치는 날이 오고,
 * 그날 국내와 해외가 서로 다른 방향을 저장한다.
 *
 * **실측으로 확정된 것과 아직 아닌 것을 섞지 말 것:**
 * - **해외** 응답은 값에 마이너스를 싣는다(부호 있는 관례). 2026-08-13 하락일 실측 2종
 *   (`HK#HS`·`.DJI`)에서 `ovrs_nmix_prdy_vrss: "-75.03"`이 `prdy_vrss_sign: "5"`(하락)와
 *   함께 왔다.
 * - **국내** 응답이 하락일에 부호를 싣는지는 **여전히 모른다** — 실측(2026-08-12)이 상승일뿐이었다.
 *   같은 응답의 `dryy_lwpr_vrss_prpr_rate`가 `"-56.02"`인 걸 보면 KIS는 어떤 필드엔 부호를 싣는다.
 *
 * 어느 관례든 이 규칙은 **결과가 같다** — 그게 이 설계의 요점이다. 해외 실측은 방어적으로 세운
 * 규칙이 원본을 그대로 재현한다는 것을 확인해 줬을 뿐, 규칙을 한쪽 관례로 좁힐 근거는 아니다.
 *
 * 다만 절댓값은 **모순도 같이 지운다**. 원본 부호가 `prdy_vrss_sign`과 어긋나면
 * (예: 값 `-233.51` + 부호 `2`) 그건 우리가 필드 뜻을 잘못 알고 있다는 뜻이므로 거부한다.
 * 놓치면 하락한 날을 상승으로 저장한다. 거부만 하고 원본 부호로 방향을 추론하지는 않는다 —
 * 자세한 규칙은 [magnitude].
 */
internal object IndexSignRule {

    /**
     * KIS 관례: 1=상한 2=상승 3=보합 4=하한 5=하락.
     * **실측으로 확인된 것은 2(국내 상승일)와 5(해외 하락일)뿐이다.** 그 밖의 값은 거부한다 —
     * 기본값을 상승으로 두면 모르는 코드가 왔을 때 하락을 상승으로 저장한다.
     */
    fun direction(sign: String): Int = when (sign) {
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
     * 국내가 부호 있는 관례를 쓰는지 없는 관례를 쓰는지는 아직 실측으로 확정되지 않았으므로
     * (해외는 부호 있는 관례로 확인됐다 — 위 클래스 KDoc),
     * **어느 쪽도 가정하지 않고 모순만** 거부한다.
     * - 값이 음수인데 코드가 상승(1·2)이나 보합(3) → 모순, 거부
     * - 값이 음수이고 코드가 하락(4·5) → 부호 있는 관례로 일관, 통과
     * - 값이 0 이상 → 어느 코드와도 모순이 아니다(부호 없는 관례), 통과
     *
     * 값을 [BigDecimal]로 **이미 파싱해서** 받는다. 국내·해외가 필드명 규약이 달라
     * (`bstp_` vs `ovrs_`) 이 함수가 응답 맵에서 직접 꺼낼 수 없기 때문이다.
     * [key]는 오류 메시지에만 쓴다 — 어느 필드가 모순인지 운영자가 알아야 한다.
     */
    fun magnitude(
        raw: BigDecimal,
        key: String,
        sign: String,
        direction: Int,
    ): BigDecimal {
        if (raw.signum() < 0 && direction >= 0) {
            throw KisIndexException(
                "KIS 지수 응답의 부호가 서로 모순됩니다: $key='${raw.toPlainString()}'(음수)인데 " +
                    "prdy_vrss_sign='$sign'(${if (direction == 0) "보합" else "상승"})입니다",
            )
        }
        return raw.abs()
    }
}
