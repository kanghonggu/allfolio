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
     * @return 걸린 항목. 첫 건에서 멈추지 않고 모두 담는다 — 파싱이 어긋난 응답은
     *         보통 한 곳만 틀리지 않고, 운영자는 어디까지 망가졌는지를 한 번에 봐야 한다.
     *         비어 있으면 저장 가능
     */
    fun check(quote: IndexQuote): List<String> {
        val anomalies = mutableListOf<String>()

        // 파싱이 실패해 0으로 떨어지면 지수가 0이 된다. 지수는 0이 될 수 없다
        if (quote.price <= BigDecimal.ZERO) {
            anomalies += "${quote.indexCode} 현재가가 0 이하입니다 (${plain(quote.price)})"
        }

        // 전일종가가 0이면 등락률을 계산할 수 없다. 나누지 않고 이 검사만 건너뛴다
        if (quote.prevClose > BigDecimal.ZERO) {
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

        return anomalies
    }

    /** 나눗셈이 붙인 뒷자리 0과 지수 표기를 걷어내 운영자가 읽을 수 있는 숫자로 만든다 */
    private fun plain(value: BigDecimal): String =
        value.stripTrailingZeros().toPlainString()
}
