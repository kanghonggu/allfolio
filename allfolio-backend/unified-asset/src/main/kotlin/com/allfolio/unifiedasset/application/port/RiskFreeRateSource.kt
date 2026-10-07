package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 샤프 비율의 무위험 수익률 — `market_rate`(AF-102)에 수집된 값을 읽는다.
 *
 * 상수(예전 대시보드 3.5%, B-04 라벨 5%)를 쓰지 않는다. 두 화면이 서로 다른 숫자를 박아 두고
 * 어느 쪽도 데이터에서 오지 않았다.
 */
fun interface RiskFreeRateSource {
    /** [asOf] 당일 또는 그 이전의 가장 최근 관측. 하나도 없으면 null — 값을 지어내지 않는다 */
    fun latest(asOf: LocalDate): RiskFreeRate?
}

/**
 * @param code      `market_rate.rate_code` (예: `CD_91D`)
 * @param quoteDate 그 값의 기준일 — 화면에 같이 보인다. 공휴일이 끼면 [RiskFreeRateSource.latest]의 asOf보다 앞선다
 * @param ratePct   연 % (예: 3.12 = 연 3.12%)
 */
data class RiskFreeRate(val code: String, val quoteDate: LocalDate, val ratePct: BigDecimal)
