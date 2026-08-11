package com.allfolio.fx

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * ECOS(한국은행 경제통계시스템) 접속 설정.
 *
 * series는 통화별 시계열 좌표다. 통계표·항목 코드는 ECOS 사이트에서 확인한 값을 넣는다 —
 * 추정한 코드는 조용히 0건을 반환해서 "코드가 틀렸는지 기간이 빈 건지" 구분되지 않는다.
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
    )
}
