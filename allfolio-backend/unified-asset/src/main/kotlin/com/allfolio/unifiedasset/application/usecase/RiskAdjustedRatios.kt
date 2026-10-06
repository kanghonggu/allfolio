package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.ReturnsCalculator
import com.allfolio.risk.domain.RiskEngine
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/**
 * 샤프·칼마 비율 — B-04 리스크 화면과 대시보드가 같이 쓴다.
 *
 * 규약(2026-10-06 이 작업 세션에서 사용자가 선택지 중 고른 답):
 * - 창: **설정 이후 전체**. 연환산 수익률·변동성·MDD를 모두 같은 창에서 잰다.
 *   위쪽 카드의 30일 변동성·MDD([FlowAdjustedRiskSeries])와는 창이 다르다.
 * - 무위험 수익률: `market_rate`의 CD(91일) — 호출자가 `RiskFreeRateSource`로 읽어 넘긴다.
 * - 최소 데이터: 구간 수익률 [MIN_SEGMENTS]건. 미만이면 null.
 * - MDD가 0이면 칼마는 정의되지 않는다 → null.
 *
 * 수익률은 TWR 엔진([ReturnsCalculator])에서 온다 — `performance_daily.daily_return`은 입출금
 * 미조정이라 입금이 수익으로 잡힌다. 변동성·MDD는 [RiskEngine]에 같은 구간 수익률을 넣는다.
 */
object RiskAdjustedRatios {

    const val MIN_SEGMENTS = 30
    private val MC = MathContext(20, RoundingMode.HALF_UP)
    private const val RATIO_SCALE = 4

    data class Result(
        /** 연환산 TWR, ratio(0.12 = 12%) */
        val annualizedReturn: BigDecimal,
        /** 창 전체 구간 수익률의 σ×√252, ratio */
        val annualizedVolatility: BigDecimal,
        /** 창 전체 MDD, ratio(0 이하) */
        val maxDrawdown: BigDecimal,
        val sharpe: BigDecimal?,
        val calmar: BigDecimal?,
        val segments: Int,
    )

    /**
     * @param riskFreeRatePct 연 %(3.12 = 3.12%). null이면 샤프만 null — 칼마는 무위험 수익률을 안 쓴다
     * @return 구간 수익률이 [MIN_SEGMENTS]건 미만이거나 기간 수익률이 −100% 이하면 null
     */
    fun compute(navSeries: List<NavPoint>, flows: List<Flow>, riskFreeRatePct: BigDecimal?): Result? {
        val sorted = navSeries.sortedBy { it.date }
        val segs = ReturnsCalculator.segmentReturns(sorted, flows)
        if (segs.size < MIN_SEGMENTS) return null

        // 기산일 = 첫 유효 구간의 시작 관측일. 연동 전 NAV 0 행이 앞에 깔려 있으면 그 날짜들은 세지 않는다
        val inception = sorted.last { it.date < segs.first().date }.date
        val end = sorted.last().date
        val days = ChronoUnit.DAYS.between(inception, end)
        if (days <= 0) return null

        val twr = ReturnsCalculator.calculate(sorted, flows, inception, end).twr ?: return null
        val growth = (BigDecimal.ONE + twr).toDouble()
        if (growth <= 0.0) return null
        val annualizedReturn = (growth.pow(365.0 / days) - 1.0).toBigDecimal(MC)

        val risk = RiskEngine.calculate(segs.map { it.ratio })
        val vol = risk.annualizedVolatility
        val mdd = risk.maxDrawdown

        val sharpe = if (riskFreeRatePct != null && vol.signum() > 0) {
            val rf = riskFreeRatePct.divide(BigDecimal(100), MC)
            (annualizedReturn - rf).divide(vol, RATIO_SCALE, RoundingMode.HALF_UP)
        } else null
        val calmar = if (mdd.signum() < 0) {
            annualizedReturn.divide(mdd.abs(), RATIO_SCALE, RoundingMode.HALF_UP)
        } else null

        return Result(annualizedReturn, vol, mdd, sharpe, calmar, segs.size)
    }
}
