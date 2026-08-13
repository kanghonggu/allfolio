package com.allfolio.fx

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 하루치 환율. **이미 1단위 기준으로 정규화돼 있다.**
 *
 * 정규화를 소스 책임으로 둔 이유: ECOS는 JPY를 100엔 단위로 고시해 나눗셈이 필요하지만
 * Upbit 일봉에는 그런 개념이 아예 없다. 서비스에 두면 Upbit 값에 엉뚱한 제수가 걸린다.
 */
data class DailyRate(val baseDate: LocalDate, val rateKrw: BigDecimal)

/**
 * @param rates   요청 구간의 일별 환율. 비어 있으면 호출자가 기존 값을 덮지 않고 중단한다
 * @param skipped 소스가 파싱 단계에서 버린 행 수 — [BackfillSummary.skipped]로 그대로 나간다
 */
data class SourceFetch(val rates: List<DailyRate>, val skipped: Int)

/**
 * 과거 환율 한 소스.
 *
 * **가져오기만 소스별이고 저장하기는 공용이다.** 0건 중단·범위 밖 제거·중복 접기·
 * inserted/updated/unchanged 계수는 [FxRateBackfillService]가 한 벌만 갖는다 —
 * ECOS를 겪으며 생긴 방어지만 소스와 무관하게 옳다.
 */
interface HistoricalRateSource {
    /** `fx_rate_daily.source`에 들어갈 값 */
    val sourceName: String

    fun supports(currency: String): Boolean

    /** 실패는 예외로 알린다 — 호출자가 상태 코드로 옮긴다. */
    fun fetch(currency: String, from: LocalDate, to: LocalDate): SourceFetch
}
