package com.allfolio.realasset

import java.math.BigDecimal
import java.time.LocalDate

/** `market_commodity_quote` 한 행에서 평가에 필요한 것만 */
data class CommodityQuote(
    val tradeDate: LocalDate,
    val price: BigDecimal,
    /** 행에 저장된 단위. **상수로 가정하지 않는다** — AF-108이 이걸 행에 둔 이유가 그것이다 */
    val unit: String,
)

/**
 * 시세 조회 포트. 구현(JPA 어댑터)은 G4가 붙인다.
 *
 * 평가 어댑터가 JPA 인터페이스를 직접 받지 않게 하는 얇은 층이다 —
 * `CommodityCollectService.Store`/`JpaCommodityStore`와 같은 배치·같은 이유다.
 */
interface CommodityQuoteLookup {
    /**
     * [code]의 [asOf] **이하** 가장 최근 관측. 없으면 null.
     *
     * **날짜 하한을 걸지 않는다.** 관측된 연속 공백이 최대 3일(2026-07-17~19 · 08-15~17)이고
     * 그것도 잰 구간 안의 값일 뿐이다. "직전 1영업일"로 좁히면 긴 연휴에 null이 나온다.
     */
    fun latestAsOf(code: String, asOf: LocalDate): CommodityQuote?
}
