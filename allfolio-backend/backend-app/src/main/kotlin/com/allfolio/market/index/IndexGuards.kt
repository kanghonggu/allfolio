package com.allfolio.market.index

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 저장 전 안전장치. KIS는 공식 API지만 값이 전부 **문자열**로 오기 때문에,
 * 소수점이 한 칸 밀리거나 단위가 %↔비율로 바뀌거나 필드명이 옮겨 붙어도
 * 파싱은 멀쩡히 성공하고 틀린 숫자만 남는다. 응답이 끊기는 쪽은 예외로 터지니
 * 여기서 볼 것은 "파싱은 됐는데 틀린 값"뿐이다.
 *
 * 판정만 하고 저장은 하지 않는다 — DB도 HTTP도 없이 검증할 수 있어야 하기 때문이다.
 * 반환이 비어 있으면 저장해도 좋다는 뜻이다.
 *
 * **급변동 가드는 일부러 두지 않았다.** AF-99의 환율 가드는 2%를 넘으면 막지만,
 * 지수는 하루 몇 %씩 정상적으로 움직이고 폭락일엔 10%도 넘긴다. 크기로 막으면
 * 데이터가 가장 필요한 날의 데이터를 버린다. 대신 값끼리 서로 맞는지만 본다 —
 * "숫자가 크다"는 시장 사건이고, "숫자끼리 모순된다"가 파싱 실패다.
 */
@Component
class IndexGuards {

    companion object {
        /**
         * 등락률 허용 오차(%p). KIS가 등락률을 소수 둘째 자리로 반올림해 주므로
         * (233.51/6345.53 = 3.6799 → 응답 3.68) 그 반올림 폭까지만 허용한다.
         */
        private val RATE_TOLERANCE = BigDecimal("0.05")
        private val HUNDRED = BigDecimal("100")
        private const val RATE_SCALE = 6
    }

    /**
     * @param reportedPrevClose 응답이 **직접 준** 전일종가(해외의 `ovrs_nmix_prdy_clpr`).
     *        [IndexQuote.prevClose]는 `현재가 − 전일대비`로 역산한 값이므로, 이 둘은
     *        서로 독립적인 출처다. 어긋나면 필드가 밀렸거나 소수점이 틀린 것이다.
     *
     *        **기본값이 null인 이유:** 국내 응답에는 전일종가 필드가 아예 없다. 즉 null은
     *        "출처가 안 준다"는 뜻이지 "호출부가 빠뜨렸다"가 아니다. 이 둘이 타입으로
     *        구분되지 않는 위험은 감수한다 — 전일종가를 줄 수 없는 국내 호출부에
     *        `null`을 억지로 적게 만드는 편이 더 나쁘다.
     * @return 걸린 항목. 첫 건에서 멈추지 않고 모두 담는다 — 파싱이 어긋난 응답은
     *         보통 한 곳만 틀리지 않고, 운영자는 어디까지 망가졌는지를 한 번에 봐야 한다.
     *         비어 있으면 저장 가능
     */
    fun check(quote: IndexQuote, reportedPrevClose: BigDecimal? = null): List<String> {
        val anomalies = mutableListOf<String>()

        // 파싱이 실패해 0으로 떨어지면 지수가 0이 된다. 지수는 0이 될 수 없다
        if (quote.price <= BigDecimal.ZERO) {
            anomalies += "${quote.indexCode} 현재가가 0 이하입니다 (${plain(quote.price)})"
        }

        // 전일종가는 price - change라 응답 어느 쪽이 틀려도 여기로 흘러든다.
        // 0 이하면 등락률을 계산할 수 없으니 나눗셈은 건너뛰되, **건너뛰고 조용히 통과시키면 안 된다** —
        // 반환이 비면 "저장해도 좋다"는 뜻이라 이 클래스의 유일한 내용 검사를 스스로 꺼 버리는 셈이다.
        // 예: 현재가가 잘려서 온 응답(prpr "100.00", vrss "233.51")은 전일종가 -133.51로 떨어지는데,
        // 그게 바로 이 가드가 존재하는 이유인 "파싱은 됐는데 틀린 값"이다.
        if (quote.prevClose <= BigDecimal.ZERO) {
            anomalies += "${quote.indexCode} 전일종가가 0 이하입니다 " +
                "(${plain(quote.prevClose)}, 현재가 ${plain(quote.price)}, 전일대비 ${plain(quote.change)})"
        } else {
            val computed = quote.change
                .divide(quote.prevClose, RATE_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
            if ((computed - quote.changeRate).abs() > RATE_TOLERANCE) {
                // 임계값을 문자열에 박아두면 상수를 조정한 날 메시지가 코드에 없는 숫자를 주장한다
                val limit = plain(RATE_TOLERANCE)
                anomalies += "${quote.indexCode} 등락률이 값과 어긋납니다 " +
                    "(계산 ${plain(computed)}%, 응답 ${plain(quote.changeRate)}%, 허용 ±${limit}%p, " +
                    "전일종가 ${plain(quote.prevClose)}, 전일대비 ${plain(quote.change)})"
            }
        }

        // 해외 응답만 전일종가를 직접 준다. 그래서 여기서 처음으로 **독립적인 출처 둘**이 생긴다 —
        // 역산값(현재가 − 전일대비)과 응답값. 국내는 역산 말고는 대조할 것이 없어 이 검사가 없었다.
        // 등락률 검사와는 별개다: 등락률은 change와 prevClose끼리만 맞으면 통과하므로,
        // 셋이 통째로 다른 지수의 값이어도 등락률은 멀쩡히 맞는다.
        //
        // **허용오차를 두지 않는다 — 정확히 일치해야 한다.** 지나가다 "장중엔 두 출처가 갈라질 수
        // 있으니 오차를 두자"고 완화하지 말 것. 근거 셋:
        //  1. 2026-08-13 실측(HK#HS)은 **홍콩 장중 시각**에 찍은 것인데도 output1의 현재가와
        //     output2[0]의 현재가가 25365.14로 완전히 같았다. 장중에도 갈라지지 않는다는 실측이다
        //  2. 실제 수집은 전부 장 마감 이후에 돈다(미국·유럽 21:30 UTC, 아시아 08:30 UTC)
        //  3. 어긋남은 시장 사건이 아니라 파싱 실패다. 오차를 두면 "소수점 한 칸"처럼
        //     이 클래스가 잡으라고 있는 어긋남이 그 폭 안에 숨는다
        //
        // 비교는 compareTo로 한다. equals/==는 스케일까지 보므로 6345.53 != 6345.5300이 되어
        // 자릿수만 다른 정상 응답을 이상으로 신고한다.
        if (reportedPrevClose != null && reportedPrevClose.compareTo(quote.prevClose) != 0) {
            anomalies += "${quote.indexCode} 응답 전일종가가 역산값과 다릅니다 " +
                "(응답 ${plain(reportedPrevClose)}, 역산 ${plain(quote.prevClose)}, " +
                "현재가 ${plain(quote.price)}, 전일대비 ${plain(quote.change)})"
        }

        return anomalies
    }

    /** 나눗셈이 붙인 뒷자리 0과 지수 표기를 걷어내 운영자가 읽을 수 있는 숫자로 만든다 */
    private fun plain(value: BigDecimal): String =
        value.stripTrailingZeros().toPlainString()
}
