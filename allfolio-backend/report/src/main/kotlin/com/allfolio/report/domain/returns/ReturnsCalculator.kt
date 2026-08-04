package com.allfolio.report.domain.returns

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

data class NavPoint(val date: LocalDate, val nav: BigDecimal)

/** amountKrw: 입금 양수, 출금 음수 */
data class Flow(val date: LocalDate, val amountKrw: BigDecimal)

data class PeriodReturns(
    val twr: BigDecimal?,
    val mwr: BigDecimal?,
    val startNav: BigDecimal?,
    val endNav: BigDecimal?,
    val netFlow: BigDecimal,
    val investmentPnl: BigDecimal?,
)

/**
 * TWR/MWR 수익률 계산 — abor npsRor의 NAV+현금흐름 조정 방식 이식.
 * 순수 함수: 스프링·DB 무관. NAV 시계열은 구멍(미관측일)을 허용하며
 * 관측일 사이 "구간" 단위로 체인링킹한다.
 */
object ReturnsCalculator {

    private val MC = MathContext(20, RoundingMode.HALF_UP)

    fun calculate(navSeries: List<NavPoint>, flows: List<Flow>, from: LocalDate, to: LocalDate): PeriodReturns {
        val series = navSeries.filter { it.date in from..to }.sortedBy { it.date }
        val periodFlows = flows.filter { it.date in from..to }

        if (series.size < 2) {
            return PeriodReturns(
                twr = null, mwr = null,
                startNav = series.firstOrNull()?.nav, endNav = series.lastOrNull()?.nav,
                netFlow = periodFlows.fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw },
                investmentPnl = null,
            )
        }

        val startNav = series.first().nav
        val endNav = series.last().nav
        // 분해 항등식(end = start + netFlow + pnl)은 기초 관측 이후~기말 관측까지의 플로우 기준
        val effectiveFlows = periodFlows.filter { it.date > series.first().date && it.date <= series.last().date }
        val effectiveNet = effectiveFlows.fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }

        return PeriodReturns(
            twr = twr(series, effectiveFlows),
            mwr = xirrPeriodReturn(series.first(), series.last(), effectiveFlows),
            startNav = startNav,
            endNav = endNav,
            netFlow = effectiveNet,
            investmentPnl = endNav - startNav - effectiveNet,
        )
    }

    /**
     * 기간 수익률(percent, 0~100 스케일) — 대시보드·performance 리포트 공용 (QA 후속 #3).
     * 시계열 첫 관측이 cutoff 이후면(커버리지 미달) null — 부분 시계열로 전체 기간
     * 수익률을 만들어내는 왜곡(+2060%) 대신 FE가 '데이터 부족'으로 표시하게 한다.
     * 기저는 cutoff 이전(포함) 마지막 관측일.
     */
    fun periodTwrPercent(
        navSeries: List<NavPoint>,
        flows: List<Flow>,
        cutoff: LocalDate,
        asOf: LocalDate,
    ): BigDecimal? {
        if (navSeries.size < 2) return null
        val sorted = navSeries.sortedBy { it.date }
        if (sorted.first().date.isAfter(cutoff)) return null
        val anchor = sorted.last { !it.date.isAfter(cutoff) }.date
        return calculate(sorted, flows, anchor, asOf).twr
            ?.multiply(BigDecimal(100))
            ?.setScale(2, RoundingMode.HALF_UP)
    }

    /** 구간별 r_i = (NAV_i − NAV_{i−1} − 순플로우_i) / (NAV_{i−1} + 입금_i) 체인링킹 */
    private fun twr(series: List<NavPoint>, flows: List<Flow>): BigDecimal {
        var product = BigDecimal.ONE
        for (i in 1 until series.size) {
            val prev = series[i - 1]
            val cur = series[i]
            val window = flows.filter { it.date > prev.date && it.date <= cur.date }
            val net = window.fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }
            val inflow = window.filter { it.amountKrw > BigDecimal.ZERO }
                .fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }
            val denominator = prev.nav + inflow
            // 전액 출금 후 재개 등 분모≤0 구간은 수익률 판단 불가 — r=0으로 건너뜀 (v1 단순화)
            if (denominator <= BigDecimal.ZERO) continue
            val r = (cur.nav - prev.nav - net).divide(denominator, MC)
            product = product.multiply(BigDecimal.ONE + r, MC)
        }
        return product - BigDecimal.ONE
    }

    /** XIRR(연율)을 풀고 기간 수익률로 환산해 반환. 미수렴 시 null */
    private fun xirrPeriodReturn(start: NavPoint, end: NavPoint, flows: List<Flow>): BigDecimal? {
        val cashFlows = buildList {
            add(start.date to -start.nav.toDouble())
            flows.forEach { add(it.date to -it.amountKrw.toDouble()) }
            add(end.date to end.nav.toDouble())
        }
        val t0 = start.date
        val days = ChronoUnit.DAYS.between(start.date, end.date).toDouble()
        if (days <= 0.0) return null

        fun npv(rate: Double): Double = cashFlows.sumOf { (date, amount) ->
            val years = ChronoUnit.DAYS.between(t0, date).toDouble() / 365.0
            amount / (1.0 + rate).pow(years)
        }

        val annual = solveNewton(::npv) ?: solveBisection(::npv) ?: return null
        val periodReturn = (1.0 + annual).pow(days / 365.0) - 1.0
        if (periodReturn.isNaN() || periodReturn.isInfinite()) return null
        return BigDecimal(periodReturn, MathContext(10, RoundingMode.HALF_UP))
    }

    private fun solveNewton(npv: (Double) -> Double): Double? {
        var rate = 0.1
        repeat(100) {
            val f = npv(rate)
            if (abs(f) < 1e-8) return rate
            val h = 1e-6
            val df = (npv(rate + h) - f) / h
            if (df == 0.0 || df.isNaN()) return null
            val next = rate - f / df
            if (next <= -0.9999 || next.isNaN() || next.isInfinite()) return null
            rate = next
        }
        return null
    }

    private fun solveBisection(npv: (Double) -> Double): Double? {
        var lo = -0.9999
        var hi = 10.0
        var fLo = npv(lo)
        if (fLo * npv(hi) > 0) return null
        repeat(200) {
            val mid = (lo + hi) / 2
            val fMid = npv(mid)
            if (abs(fMid) < 1e-8) return mid
            if (fLo * fMid < 0) { hi = mid } else { lo = mid; fLo = fMid }
        }
        return (lo + hi) / 2
    }
}
