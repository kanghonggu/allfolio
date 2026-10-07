package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * 지수의 기간 수익률(percent) — **리포트(성과·벤치마크)와 대시보드가 이 하나를 같이 쓴다.**
 *
 * 기저는 **기간 시작 이전(포함) 마지막 종가**다. YTD면 1월 1일 이전 마지막 종가 = 보통 전년 마지막
 * 영업일 종가로, 지수 YTD를 말하는 일반적인 방식이다. 포트폴리오 쪽 앵커
 * ([com.allfolio.report.domain.returns.ReturnsCalculator.periodTwrPercent]: 기간 시작 이전 마지막 관측)와도 같은 규칙이다.
 *
 * 대시보드는 예전에 `1월 1일 + 5일` 이하 마지막 종가를 기저로 따로 계산했다 — 그해 첫 주의 움직임이
 * 빠져 리포트와 다른 KOSPI YTD가 나왔다. 규칙을 두 군데 복사하면 갈라진다.
 *
 * 기간 시작 [ANCHOR_LOOKBACK_DAYS]일 전부터 읽는다(휴장일 대비). 그 안에 시작 이전 종가가 없으면
 * 읽은 첫 종가를 기저로 쓴다 — 시계열이 기간 시작보다 늦게 시작한 경우다(기존 리포트 동작 그대로).
 */
object IndexPeriodReturn {

    const val ANCHOR_LOOKBACK_DAYS = 14L

    fun of(store: BenchmarkDailyStore, type: BenchmarkType, since: LocalDate, today: LocalDate): BigDecimal? =
        percent(store.series(type, since.minusDays(ANCHOR_LOOKBACK_DAYS), today), since)

    /** [rows]는 날짜 오름차순. 2건 미만이거나 기저 ≤ 0이면 null */
    fun percent(rows: List<Pair<LocalDate, BigDecimal>>, since: LocalDate): BigDecimal? {
        if (rows.size < 2) return null
        val base = (rows.lastOrNull { it.first <= since } ?: rows.first()).second
        val last = rows.last().second
        if (base <= BigDecimal.ZERO) return null
        return last.subtract(base).divide(base, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
    }
}
