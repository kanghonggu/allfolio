package com.allfolio.fx.hana

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 저장 전 안전장치. 하나은행은 공식 API가 아니라 마크업이 바뀌면 예외가 아니라
 * 조용히 빈/부분 테이블을 돌려준다.
 *
 * 판정만 하고 저장은 하지 않는다 — DB도 HTTP도 없이 검증할 수 있어야 하기 때문이다.
 * 반환이 비어 있으면 저장해도 좋다는 뜻이다.
 */
@Component
class HanaFxGuards {

    companion object {
        /** 평가 경로가 USD를 쓰므로 USD 없는 수집은 쓸모가 없다 */
        private const val REQUIRED = "USD"
        private val MIN_ROW_RATIO = BigDecimal("0.5")
        private val MAX_CHANGE_RATIO = BigDecimal("0.02")
    }

    /**
     * @param previousRates   통화별 직전 고시 매매기준율. 없는 통화는 변동 검사를 건너뛴다
     * @param previousRowCount 직전 수집의 통화 수. null·0이면 비교 대상이 없으므로 행 수 검사를 건너뛴다.
     *                        0을 건너뛰는 것은 의도된 계약이다 — 직전 회차 조회가 빈 목록을 주면
     *                        호출자가 null 대신 0을 넘기기 쉬운데, 없는 데이터와 비교해 막으면 안 된다
     * @param force           변동 가드만 무시한다. 실제로 임계값 넘게 움직인 날 영구히 막히는 걸 푸는 용도
     * @return 걸린 항목. 여러 개가 동시에 걸리면 모두 담는다 — 빈 테이블은 USD 부재와 행 급감이
     *         함께 걸리고, 그 조합이 "마크업이 바뀌었다"와 "USD가 빠졌다"를 가른다.
     *         비어 있으면 저장 가능
     */
    fun check(
        rows: List<HanaFxRow>,
        previousRates: Map<String, BigDecimal>,
        previousRowCount: Int?,
        force: Boolean,
    ): List<String> {
        val anomalies = mutableListOf<String>()

        if (rows.none { it.currency == REQUIRED }) {
            anomalies += "필수 통화 $REQUIRED 가 응답에 없습니다 (통화 ${rows.size}개)"
        }

        // 첫 수집이면 비교 대상이 없다. 여기서 막으면 수집을 시작할 수 없다
        if (previousRowCount != null) {
            val threshold = BigDecimal(previousRowCount).multiply(MIN_ROW_RATIO)
            if (BigDecimal(rows.size) < threshold) {
                // 직전 행 수만 보이면 조금만 줄어도 걸리는 것처럼 읽힌다 — 적용된 임계값을 같이 준다
                val limit = threshold.stripTrailingZeros().toPlainString()
                anomalies += "행 수가 직전 수집의 절반 미만입니다 (${rows.size} < $limit, 직전 $previousRowCount)"
            }
        }

        if (!force) {
            rows.forEach { row ->
                val previous = previousRates[row.currency] ?: return@forEach
                if (previous <= BigDecimal.ZERO) return@forEach
                val change = (row.baseRate - previous).abs()
                    .divide(previous, 6, RoundingMode.HALF_UP)
                if (change > MAX_CHANGE_RATIO) {
                    // 임계값을 문자열에 박아두면 상수를 조정한 날 메시지가 코드에 없는 숫자를 주장한다
                    val limit = MAX_CHANGE_RATIO.movePointRight(2).stripTrailingZeros().toPlainString()
                    anomalies += "${row.currency} 변동이 $limit%를 넘습니다 ($previous → ${row.baseRate})"
                }
            }
        }

        return anomalies
    }
}
