package com.allfolio.market.rate

import java.math.BigDecimal
import java.time.LocalDate

/** 하루치 금리 한 건. 값은 연 %다 */
data class RateObservation(val quoteDate: LocalDate, val value: BigDecimal)

/** @param skipped 소스가 파싱 단계에서 버린 행 수 — 요약의 skippedRows로 그대로 나간다 */
data class RateFetch(val rows: List<RateObservation>, val skipped: Int)

/**
 * 금리 한 소스.
 *
 * **가져오기만 소스별이고 저장하기는 공용이다.** 구간 밖 날짜 제거·0건 처리·중복 접기·
 * inserted/updated/unchanged 계수·종목별 실패 격리는 [RateCollectService]가 한 벌만 갖는다 —
 * ECOS를 겪으며 생긴 방어지만 소스와 무관하게 옳다. AF-100의 `HistoricalRateSource`가 같은 판단이다.
 *
 * **환율 포트와 한 군데 다르다.** 환율은 호출자가 통화를 지목하므로 `supports(currency)`로 묻지만,
 * 금리는 수집 대상이 설정에서 열거되므로 소스가 자기 코드 목록을 내놓는다.
 */
interface RateSource {
    /** `market_rate.source`에 들어갈 값 */
    val sourceName: String

    /** 이 소스가 담당하는 canonical 코드. 설정에서 온다 */
    val codes: List<String>

    /**
     * `from..to`는 포함 범위이고, 범위 밖 날짜가 섞여 와도 된다 — 서비스가 걸러낸다.
     * 실패는 예외로 알린다 — 서비스가 종목별로 잡아 요약의 failures로 옮긴다.
     */
    fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch
}
