package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.ReturnsCalculator
import com.allfolio.risk.domain.RiskEngine

/**
 * B-04 리스크 화면의 시계열을 NAV + 외부 플로우에서 읽는 시점에 만든다.
 *
 * **`performance_daily.daily_return`·`risk_daily`를 쓰지 않는다.** daily_return은
 * `(NAV_t − NAV_{t−1}) / NAV_{t−1}`라 입금일이 수익, 출금일이 손실로 잡히고, risk_daily는
 * 그 값을 그대로 먹은 결과다. 1,000만 원 입금 하루가 변동성·VaR·MDD를 통째로 오염시킨다.
 * 저장값을 고치려면 스키마·소급 정정이 필요해 읽는 쪽에서 다시 계산한다.
 *
 * 일 수익률은 TWR 엔진([ReturnsCalculator.segmentReturns])을 그대로 쓴다 — 기간 수익률 카드와
 * 리스크 화면이 같은 수익률을 본다. 지표 계산은 기존 [RiskEngine] 그대로.
 *
 * 창은 날짜마다 직전 [WINDOW_DAYS]일 `(d − 30, d]`의 구간 수익률이다. 구간이 [MIN_RETURNS]건
 * 미만인 날은 내지 않는다 — 한 건으로는 표준편차가 0으로 나와 "변동성 0%"로 읽힌다.
 *
 * 대시보드(`GetDashboardUseCase`)도 이 시계열의 마지막 날을 쓴다 — 두 화면이 같은 MDD·변동성·VaR를 보인다.
 */
object FlowAdjustedRiskSeries {

    const val WINDOW_DAYS = 30L
    private const val MIN_RETURNS = 2

    fun build(navSeries: List<NavPoint>, flows: List<Flow>): List<DailyRisk> {
        val segs = ReturnsCalculator.segmentReturns(navSeries, flows)
        return segs.mapNotNull { anchor ->
            val windowStart = anchor.date.minusDays(WINDOW_DAYS)
            val window = segs.filter { it.date > windowStart && !it.date.isAfter(anchor.date) }
            if (window.size < MIN_RETURNS) return@mapNotNull null
            val risk = RiskEngine.calculate(window.map { it.ratio })
            DailyRisk(
                date = anchor.date,
                volatility = risk.volatility,
                annualizedVolatility = risk.annualizedVolatility,
                var95 = risk.var95,
                maxDrawdown = risk.maxDrawdown,
            )
        }
    }
}
