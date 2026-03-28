package com.allfolio.risk.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.sqrt

/**
 * 포트폴리오 리스크 계산 엔진 (순수 도메인 서비스)
 *
 * 원칙:
 * - 상태 저장 없음
 * - side-effect 없음
 * - DB 접근 없음
 *
 * 계산:
 *   Volatility          = std(dailyReturns)
 *   AnnualizedVol       = σ × √252
 *   Historical VaR 95%  = 정렬 후 하위 5% 지점 수익률
 *   MDD                 = max((peak - trough) / peak) 누적 수익률 기준
 */
object RiskEngine {

    private val SCALE = 10
    private val ROUNDING = RoundingMode.HALF_UP
    private val MC = MathContext(SCALE + 4, ROUNDING)
    private val TRADING_DAYS = 252.0

    /** VaR 계산을 위한 최소 데이터 포인트 */
    private val MIN_VAR_POINTS = 2

    /**
     * @param dailyReturns 일간 수익률 리스트 (예: 0.0123, -0.0045)
     *                     시간순 정렬 (MDD 계산에 사용)
     */
    fun calculate(dailyReturns: List<BigDecimal>): RiskSnapshot {
        if (dailyReturns.isEmpty()) throw RiskException.emptyReturns()
        if (dailyReturns.size < MIN_VAR_POINTS) {
            return RiskSnapshot(
                volatility           = BigDecimal.ZERO,
                annualizedVolatility = BigDecimal.ZERO,
                var95                = dailyReturns.first().setScale(SCALE, ROUNDING),
                maxDrawdown          = BigDecimal.ZERO,
            )
        }

        val returns = dailyReturns.map { it.toDouble() }

        val volatility           = computeStdDev(returns)
        val annualizedVolatility = volatility * sqrt(TRADING_DAYS)
        val var95                = computeHistoricalVar95(returns)
        val maxDrawdown          = computeMaxDrawdown(returns)

        return RiskSnapshot(
            volatility           = volatility.toBigDecimal(MC).setScale(SCALE, ROUNDING),
            annualizedVolatility = annualizedVolatility.toBigDecimal(MC).setScale(SCALE, ROUNDING),
            var95                = var95.toBigDecimal(MC).setScale(SCALE, ROUNDING),
            maxDrawdown          = maxDrawdown.toBigDecimal(MC).setScale(SCALE, ROUNDING),
        )
    }

    // ──────────────────────────────────────────────
    // private helpers
    // ──────────────────────────────────────────────

    /**
     * 표본 표준편차 (n-1 기준 / Bessel's correction)
     */
    private fun computeStdDev(returns: List<Double>): Double {
        val n = returns.size
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (n - 1)
        return sqrt(variance)
    }

    /**
     * Historical VaR 95%
     * 수익률 정렬 → 하위 5% 인덱스 값 (음수 = 손실)
     *
     * 예: 100개 수익률 → index 4 (floor(100 × 0.05) - 1 = 4)
     */
    private fun computeHistoricalVar95(returns: List<Double>): Double {
        val sorted = returns.sorted()
        val index = (sorted.size * 0.05).toInt().coerceAtLeast(0)
        return sorted[index]
    }

    /**
     * Maximum Drawdown (MDD)
     * 누적 수익률 시계열 기반 Peak-to-Trough 최대 하락률
     *
     * cum_t = (1 + r1)(1 + r2)...(1 + rt) - 1
     * drawdown_t = (cum_t - peak_t) / (1 + peak_t)
     * MDD = min(drawdown_t)  ← 음수
     */
    private fun computeMaxDrawdown(returns: List<Double>): Double {
        var cumulative = 1.0   // 초기 자산 = 1 (지수화)
        var peak = 1.0
        var maxDrawdown = 0.0

        for (r in returns) {
            cumulative *= (1.0 + r)
            if (cumulative > peak) peak = cumulative
            val drawdown = (cumulative - peak) / peak
            if (drawdown < maxDrawdown) maxDrawdown = drawdown
        }

        return maxDrawdown  // 0 이하 음수
    }
}
