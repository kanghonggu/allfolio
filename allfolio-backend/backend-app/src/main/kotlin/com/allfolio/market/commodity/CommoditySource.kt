package com.allfolio.market.commodity

import java.math.BigDecimal
import java.time.LocalDate

/** 하루(또는 한 달)치 시세 한 건 */
data class CommodityObservation(val quoteDate: LocalDate, val value: BigDecimal)

/** @param skipped 소스가 파싱 단계에서 버린 행 수 — 요약의 skippedRows로 그대로 나간다 */
data class CommodityFetch(val rows: List<CommodityObservation>, val skipped: Int)

/**
 * 원자재 한 소스.
 *
 * **가져오기만 소스별이고 저장하기는 공용이다** — 구간 밖 날짜 제거·0건 처리·중복 접기·
 * inserted/updated/unchanged 계수·종목별 실패 격리는 `CommodityCollectService`(Task 5)가
 * 한 벌만 갖는다. `RateSource`(AF-FRED)·`HistoricalRateSource`(AF-100)와 같은 판단이다.
 *
 * 단위와 주기는 관측이 아니라 **설정**에서 온다([CommodityProperties]) — 소스가 그것을
 * 응답에 싣지 않기 때문이다. 그래서 코드가 아니라 설정을 고쳐 바꾼다.
 */
interface CommoditySource {
    /** `market_commodity_quote.source`에 들어갈 값 */
    val sourceName: String

    /** 이 소스가 담당하는 canonical 코드. 설정에서 온다 */
    val codes: List<String>

    /**
     * `from..to`는 포함 범위이고 범위 밖 날짜가 섞여 와도 된다 — 서비스가 걸러낸다.
     * 실패는 예외로 알린다 — 서비스가 종목별로 잡아 요약의 failures로 옮긴다.
     */
    fun fetch(code: String, from: LocalDate, to: LocalDate): CommodityFetch
}
