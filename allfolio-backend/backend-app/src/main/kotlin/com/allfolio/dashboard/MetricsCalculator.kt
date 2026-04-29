package com.allfolio.dashboard

import java.math.BigDecimal
import java.math.RoundingMode

enum class MetricGrade { EXCELLENT, GOOD, WARN, BAD }

object MetricsCalculator {

    fun returnToGrade(pct: BigDecimal): MetricGrade = when {
        pct >= BigDecimal("15") -> MetricGrade.EXCELLENT
        pct >= BigDecimal("5")  -> MetricGrade.GOOD
        pct >= BigDecimal.ZERO  -> MetricGrade.WARN
        else                    -> MetricGrade.BAD
    }

    fun returnToStars(pct: BigDecimal): Int = when {
        pct >= BigDecimal("20") -> 5
        pct >= BigDecimal("10") -> 4
        pct >= BigDecimal("3")  -> 3
        pct >= BigDecimal.ZERO  -> 2
        else                    -> 1
    }

    fun mddToGrade(mdd: BigDecimal): MetricGrade = when {
        mdd >= BigDecimal("-5")  -> MetricGrade.EXCELLENT
        mdd >= BigDecimal("-15") -> MetricGrade.GOOD
        mdd >= BigDecimal("-30") -> MetricGrade.WARN
        else                     -> MetricGrade.BAD
    }

    fun mddToStars(mdd: BigDecimal): Int = when {
        mdd >= BigDecimal("-5")  -> 5
        mdd >= BigDecimal("-10") -> 4
        mdd >= BigDecimal("-20") -> 3
        mdd >= BigDecimal("-30") -> 2
        else                     -> 1
    }

    fun concentrationToGrade(ratio: BigDecimal): MetricGrade = when {
        ratio <= BigDecimal("0.30") -> MetricGrade.EXCELLENT
        ratio <= BigDecimal("0.50") -> MetricGrade.GOOD
        ratio <= BigDecimal("0.70") -> MetricGrade.WARN
        else                        -> MetricGrade.BAD
    }

    fun sharpeToGrade(v: BigDecimal): MetricGrade = when {
        v >= BigDecimal("2.0") -> MetricGrade.EXCELLENT
        v >= BigDecimal("1.0") -> MetricGrade.GOOD
        v >= BigDecimal.ZERO   -> MetricGrade.WARN
        else                   -> MetricGrade.BAD
    }

    fun sharpeToStars(v: BigDecimal): Int = when {
        v >= BigDecimal("2.0") -> 5
        v >= BigDecimal("1.5") -> 4
        v >= BigDecimal("1.0") -> 3
        v >= BigDecimal("0.5") -> 2
        else                   -> 1
    }

    fun volatilityToGrade(annualPct: BigDecimal): MetricGrade = when {
        annualPct <= BigDecimal("10")  -> MetricGrade.EXCELLENT
        annualPct <= BigDecimal("20")  -> MetricGrade.GOOD
        annualPct <= BigDecimal("40")  -> MetricGrade.WARN
        else                           -> MetricGrade.BAD
    }

    fun dataWarning(dataDays: Int): String? =
        if (dataDays < 30) "단기 데이터 기반 (${dataDays}일)" else null

    fun pctDiff(portfolio: BigDecimal, benchmark: BigDecimal): BigDecimal =
        portfolio.subtract(benchmark).setScale(4, RoundingMode.HALF_UP)

    fun weightOf(value: BigDecimal, total: BigDecimal): BigDecimal =
        if (total <= BigDecimal.ZERO) BigDecimal.ZERO
        else value.divide(total, 4, RoundingMode.HALF_UP)
}
