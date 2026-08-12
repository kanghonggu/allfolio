package com.allfolio.market.index

/**
 * 스케줄 지점 (AF-101).
 *
 * KIS 지수 응답에 기준시각이 없어 조회 시각을 키로 쓸 수 없다.
 * 대신 "그날의 어느 지점인가"를 키에 넣어, cron이 밀려도 한 건으로 수렴시킨다.
 */
enum class IndexSlot { OPEN, MID, CLOSE }

/** 시장 상태. KIS 응답에 없어 우리가 판정한다. */
enum class MarketStatus(val label: String) {
    PRE_OPEN("개장전"),
    OPEN("장중"),
    CLOSED("장마감"),
}
