package com.allfolio.snapshot.domain

import java.math.BigDecimal

/**
 * 단일 날짜의 포트폴리오 성과 계산 결과.
 * 불변 객체 — 생성 후 수정 불가.
 */
data class DailyPerformanceSnapshot(
    val nav: BigDecimal,
    val dailyReturn: BigDecimal,
    val cumulativeReturn: BigDecimal,
    val benchmarkReturn: BigDecimal?,
    val alpha: BigDecimal?,
) {
    fun hasBenchmark(): Boolean = benchmarkReturn != null

    fun isPositiveDay(): Boolean = dailyReturn > BigDecimal.ZERO

    companion object {
        fun empty(nav: BigDecimal): DailyPerformanceSnapshot = DailyPerformanceSnapshot(
            nav              = nav,
            dailyReturn      = BigDecimal.ZERO,
            cumulativeReturn = BigDecimal.ZERO,
            benchmarkReturn  = null,
            alpha            = null,
        )
    }
}
