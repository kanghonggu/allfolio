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

/**
 * 통화 분해용 관측 한 건.
 *
 * 두 계열을 별도 리스트로 받으면 어긋날 수 있어 한 타입에 묶는다.
 *
 * @param nav          그날의 원화 평가액 — `performance_daily.nav` 그대로. 재계산하지 말 것
 * @param navAtPriorFx 그날 보유를 **전일 환율**로 평가한 값. 첫 관측일은 null(직전 구간이 없다).
 *                     실제 산출식은 `nav + Σ_c v_c·(r_c(전일) − r_c(당일))` — 권위 있는 nav에
 *                     환율 차이만 얹는다. `Σ v_c·r_c(전일)`로 직접 구하면 toKrw의 원 단위
 *                     반올림 때문에 환율이 안 움직인 날에도 환율 기여가 0이 아니게 된다.
 */
data class NavFxPoint(val date: LocalDate, val nav: BigDecimal, val navAtPriorFx: BigDecimal?)

/** 기간 수익의 분해 — ratio(0~1). `(1+asset)(1+fx)−1 == TWR` */
data class Attribution(val assetContribution: BigDecimal, val fxContribution: BigDecimal)

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

    /** 자산 다리가 −100%에 붙으면 환율 다리가 발산한다 */
    private val ATTRIBUTION_EPSILON = BigDecimal("1E-9")

    /**
     * 기간 수익률을 자산 기여와 환율 기여로 쪼갠다 (AF-106).
     *
     * 구간마다 환율을 전일로 얼린 평행 수익률을 만들고, 환율 다리는 나머지가 아니라
     * `(1+r)/(1+r_asset) − 1`로 **명시**한다. 나머지로 두면 교차항이 자산 쪽에 조용히
     * 흡수된다. 이 정의 덕분에 구간마다 `(1+r) = (1+r_asset)(1+r_fx)`가 정의상 성립하고,
     * 곱을 재배열하면 `(1+자산기여)(1+환율기여) = 1 + TWR`이 된다.
     *
     * [twr]과 **같은 [segments] 호출**을 쓴다 — 구간 집합이 갈라지면 위 항등식이 깨진다.
     *
     * @return 분해 불가면 null — 관측 2건 미만 / 유효 구간 없음 / [NavFxPoint.navAtPriorFx] 결측 /
     *         자산 다리가 −100%에 근접
     */
    fun attribute(
        series: List<NavFxPoint>,
        flows: List<Flow>,
        from: LocalDate,
        to: LocalDate,
    ): Attribution? {
        val s = series.filter { it.date in from..to }.sortedBy { it.date }
        if (s.size < 2) return null

        val navs = s.map { it.nav }
        val segs = segments(s.map { it.date }, navs, flows)
        if (segs.isEmpty()) return null

        var assetProduct = BigDecimal.ONE
        var fxProduct = BigDecimal.ONE

        for (seg in segs) {
            // 통화별 행이 그날 안 써졌다 — 억지로 이으면 환율 차이가 0으로 잡혀
            // 자산 쪽에 흡수된다. 분해를 포기하는 편이 정직하다.
            val frozen = s[seg.i].navAtPriorFx ?: return null
            val prevNav = navs[seg.i - 1]

            val r = (navs[seg.i] - prevNav - seg.net).divide(seg.denominator, MC)
            val rAsset = (frozen - prevNav - seg.net).divide(seg.denominator, MC)

            val onePlusAsset = BigDecimal.ONE + rAsset
            if (onePlusAsset.abs() < ATTRIBUTION_EPSILON) return null
            val onePlusFx = (BigDecimal.ONE + r).divide(onePlusAsset, MC)

            assetProduct = assetProduct.multiply(onePlusAsset, MC)
            fxProduct = fxProduct.multiply(onePlusFx, MC)
        }

        return Attribution(assetProduct - BigDecimal.ONE, fxProduct - BigDecimal.ONE)
    }

    /**
     * 구간 하나 — 관측일 i−1 → i.
     *
     * @param i          현재 관측의 인덱스 (직전은 i−1)
     * @param net        구간 순플로우 (입금 양수, 출금 음수)
     * @param denominator NAV_{i−1} + 입금. **항상 > 0** — 0 이하 구간은 [segments]가 이미 걸렀다
     */
    private data class Segment(val i: Int, val net: BigDecimal, val denominator: BigDecimal)

    /**
     * 구간 분할·분모 계산·건너뜀 규약을 여기 한 곳에만 둔다.
     *
     * **[twr]과 attribute()가 반드시 이 함수를 같이 써야 한다.** 두 계열이 서로 다른 구간
     * 집합을 돌면 `(1+자산기여)(1+환율기여) = 1+TWR` 항등식이 깨지는데, 증상이 "분해 합이
     * TWR과 미묘하게 다름"이라 눈으로 못 잡는다. 규약을 복제하면 어느 날 한쪽만 고쳐진다.
     *
     * 분모 ≤ 0인 구간(전액 출금 후 재개 등)은 수익률 판단이 불가능하므로 통째로 뺀다.
     */
    private fun segments(
        dates: List<LocalDate>,
        navs: List<BigDecimal>,
        flows: List<Flow>,
    ): List<Segment> {
        val out = mutableListOf<Segment>()
        for (i in 1 until dates.size) {
            val window = flows.filter { it.date > dates[i - 1] && it.date <= dates[i] }
            val net = window.fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }
            val inflow = window.filter { it.amountKrw > BigDecimal.ZERO }
                .fold(BigDecimal.ZERO) { acc, f -> acc + f.amountKrw }
            val denominator = navs[i - 1] + inflow
            if (denominator <= BigDecimal.ZERO) continue
            out += Segment(i, net, denominator)
        }
        return out
    }

    /** 구간별 r_i = (NAV_i − NAV_{i−1} − 순플로우_i) / (NAV_{i−1} + 입금_i) 체인링킹 */
    private fun twr(series: List<NavPoint>, flows: List<Flow>): BigDecimal {
        val navs = series.map { it.nav }
        var product = BigDecimal.ONE
        for (s in segments(series.map { it.date }, navs, flows)) {
            val r = (navs[s.i] - navs[s.i - 1] - s.net).divide(s.denominator, MC)
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
