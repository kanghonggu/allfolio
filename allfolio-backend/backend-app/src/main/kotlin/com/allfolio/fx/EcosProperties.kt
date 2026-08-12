package com.allfolio.fx

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * ECOS(한국은행 경제통계시스템) 접속 설정.
 *
 * series는 통화별 시계열 좌표다. 통계표·항목 코드는 ECOS 사이트에서 확인한 값을 넣는다 —
 * 추정한 코드는 조용히 0건을 반환해서 "코드가 틀렸는지 기간이 빈 건지" 구분되지 않는다.
 *
 * **여기에 통화를 추가하는 것만으로는 아무 효과가 없다.**
 * 이 맵은 백필이 무엇을 *채울지*만 정하고, 채운 값을 *읽을지*는
 * [UnifiedAssetFxConverterAdapter]의 `HISTORICAL` 집합이 따로 정한다.
 * 한쪽만 고치면 행은 쌓이는데 조회는 현재 환율 폴백으로 떨어진다 — 오류도 로그도 나지 않는다.
 * 통화를 늘릴 때는 반드시 양쪽을 함께 고칠 것.
 */
@ConfigurationProperties(prefix = "ecos")
data class EcosProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://ecos.bok.or.kr",
    val series: Map<String, Series> = emptyMap(),
) {
    /**
     * @param unitDivisor 고시 단위를 1단위로 되돌리는 제수.
     *                    ECOS는 JPY를 100엔 기준으로 주므로 그때 100을 넣는다. USD는 1.
     */
    data class Series(
        val statCode: String = "",
        val itemCode: String = "",
        val unitDivisor: BigDecimal = BigDecimal.ONE,
    ) {
        init {
            // 0이면 백필이 ArithmeticException으로 죽고, 음수면 더 나쁘다 — 모든 환율의 부호가 뒤집힌 채
            // fx_rate_daily를 거쳐 cash_flow.amount_krw까지 조용히 흘러간다.
            // (파서의 rate <= 0 가드는 나눗셈 전에 돌기 때문에 걸러 주지 못한다.)
            // 그래서 바인딩 시점에 막아 기동을 실패시킨다.
            require(unitDivisor > BigDecimal.ZERO) { "unit-divisor는 0보다 커야 합니다: $unitDivisor" }
        }
    }
}
