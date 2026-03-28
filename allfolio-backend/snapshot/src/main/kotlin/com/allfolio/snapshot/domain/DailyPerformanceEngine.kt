package com.allfolio.snapshot.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 포트폴리오 일간 성과 계산 엔진 (순수 도메인 서비스)
 *
 * 원칙:
 * - 상태 저장 없음
 * - side-effect 없음
 * - DB 접근 없음
 *
 * 계산식:
 *   dailyReturn      = (NAV_today - NAV_yesterday - externalCashFlow) / NAV_yesterday
 *   cumulativeReturn = (1 + prevCumReturn) × (1 + dailyReturn) - 1
 *   alpha            = portfolioReturn - benchmarkReturn
 */
object DailyPerformanceEngine {

    private val SCALE = 10
    private val ROUNDING = RoundingMode.HALF_UP
    private val ONE = BigDecimal.ONE

    /**
     * @param todayNav                오늘 NAV (= Σ 자산 시장가치)
     * @param yesterdayNav            전일 NAV
     * @param externalCashFlow        당일 외부 입출금 (입금 양수, 출금 음수)
     * @param previousCumulativeReturn 전일까지의 누적 수익률 (첫날이면 null)
     * @param benchmarkReturn         당일 Benchmark 수익률 (없으면 null)
     */
    fun calculate(
        todayNav: BigDecimal,
        yesterdayNav: BigDecimal,
        externalCashFlow: BigDecimal = BigDecimal.ZERO,
        previousCumulativeReturn: BigDecimal? = null,
        benchmarkReturn: BigDecimal? = null,
    ): DailyPerformanceSnapshot {
        validate(todayNav, yesterdayNav)

        // 첫 거래일이거나 전일 NAV = 0 이면 수익률 계산 불가
        if (yesterdayNav.compareTo(BigDecimal.ZERO) == 0) {
            return DailyPerformanceSnapshot.empty(todayNav)
        }

        val dailyReturn      = computeDailyReturn(todayNav, yesterdayNav, externalCashFlow)
        val cumulativeReturn = computeCumulativeReturn(previousCumulativeReturn, dailyReturn)
        val alpha            = benchmarkReturn?.let { dailyReturn.subtract(it).setScale(SCALE, ROUNDING) }

        return DailyPerformanceSnapshot(
            nav              = todayNav.setScale(SCALE, ROUNDING),
            dailyReturn      = dailyReturn,
            cumulativeReturn = cumulativeReturn,
            benchmarkReturn  = benchmarkReturn?.setScale(SCALE, ROUNDING),
            alpha            = alpha,
        )
    }

    // ──────────────────────────────────────────────
    // private helpers
    // ──────────────────────────────────────────────

    /**
     * TWR 일간 수익률
     * r = (NAV_t - NAV_t-1 - CF) / NAV_t-1
     */
    private fun computeDailyReturn(
        todayNav: BigDecimal,
        yesterdayNav: BigDecimal,
        externalCashFlow: BigDecimal,
    ): BigDecimal {
        val numerator = todayNav.subtract(yesterdayNav).subtract(externalCashFlow)
        return numerator.divide(yesterdayNav, SCALE, ROUNDING)
    }

    /**
     * 연결 수익률 (Chain-linking)
     * cum_t = (1 + cum_t-1) × (1 + r_t) - 1
     * 첫날(prevCum = null)은 당일 수익률 그대로
     */
    private fun computeCumulativeReturn(
        previousCumulativeReturn: BigDecimal?,
        dailyReturn: BigDecimal,
    ): BigDecimal {
        val prevFactor = ONE.add(previousCumulativeReturn ?: BigDecimal.ZERO)
        val todayFactor = ONE.add(dailyReturn)
        return prevFactor.multiply(todayFactor).subtract(ONE).setScale(SCALE, ROUNDING)
    }

    private fun validate(todayNav: BigDecimal, yesterdayNav: BigDecimal) {
        if (todayNav < BigDecimal.ZERO)     throw PerformanceException.negativeNav(todayNav)
        if (yesterdayNav < BigDecimal.ZERO) throw PerformanceException.negativeYesterdayNav(yesterdayNav)
    }
}
