package com.allfolio.market.realestate

import java.time.LocalDateTime
import java.time.YearMonth

/** 한 (시군구, 년월)을 언제 받았고 무엇이 나왔는지 */
data class RtmsFetchRecord(
    val sggCode: String,
    val month: YearMonth,
    val dealCount: Int,
    val apiCalls: Int,
    val fetchedAt: LocalDateTime,
)

/**
 * 실거래가 캐시 저장 포트.
 *
 * **가져오기(`RtmsClient`)와 저장하기를 나눈다** — 수집 서비스가 예산·재수집 판단만 하고
 * DB 방언을 모르게 하려는 것이다. `CommoditySource`/`JpaCommodityStore`와 같은 판단이다.
 */
interface RtmsDealStore {

    /**
     * 거래를 **덮어쓴다**(자연키 `(단지, 면적, 계약일, 층, 금액)` 기준).
     *
     * **덮어쓰기여야 하는 이유**: 같은 거래가 처음엔 정상으로 왔다가 나중에 해제로 바뀐다
     * (실측 2,698건 중 71건). 한 번 넣고 마는 구조면 취소된 거래가 영원히 시세에 남는다.
     *
     * @return 새로 들어간 건수
     */
    fun upsertAll(deals: List<RtmsDeal>, collectedAt: LocalDateTime): Int

    /** 이미 받은 조합인지. **없으면 null** — "0건이었다"와 "안 받았다"는 다르다 */
    fun findFetch(sggCode: String, month: YearMonth): RtmsFetchRecord?

    fun recordFetch(record: RtmsFetchRecord)
}
