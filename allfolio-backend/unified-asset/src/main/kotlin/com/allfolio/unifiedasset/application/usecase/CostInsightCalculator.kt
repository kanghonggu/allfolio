package com.allfolio.unifiedasset.application.usecase

import java.math.BigDecimal
import java.math.RoundingMode

/** 비용 보고서 사실형 하이라이트(조언 아님). 기존 집계값의 파생만 수행. */
data class CostInsight(val label: String, val value: String, val detail: String?)

object CostInsightCalculator {
    private val mc = java.math.MathContext(20)

    /** 비용률 %(0~100 스케일) → bp 정수 문자열. 예: 0.15% → "15". */
    private fun bp(pct: BigDecimal): String =
        pct.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toPlainString()

    /** a/b × 100, 2자리(b<=0 → 0). */
    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO.setScale(2)
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)

    fun build(
        totalCost: BigDecimal,
        brokerFee: BigDecimal,
        tradingTax: BigDecimal,
        costRatio: BigDecimal?,
        annualizedTer: BigDecimal?,
        costVsProfit: BigDecimal?,
        topBrokerName: String?,
        topBrokerWeight: BigDecimal?,
    ): List<CostInsight> {
        val out = mutableListOf<CostInsight>()
        if (annualizedTer != null) {
            out += CostInsight("연환산 TER", "${bp(annualizedTer)}bp", "비용률 연환산")
        }
        if (costRatio != null) {
            out += CostInsight("기간 비용률", "${costRatio.toPlainString()}% (${bp(costRatio)}bp)", null)
        }
        if (topBrokerName != null) {
            val d = topBrokerWeight?.let { "전체 비용의 ${it.toPlainString()}%" }
            out += CostInsight("최대 비용 처", topBrokerName, d)
        }
        if (totalCost > BigDecimal.ZERO) {
            out += CostInsight(
                "비용 구성",
                "매매수수료 ${pct(brokerFee, totalCost).toPlainString()}% · 거래세 ${pct(tradingTax, totalCost).toPlainString()}%",
                null,
            )
        }
        if (costVsProfit != null) {
            out += CostInsight("투자손익 대비 비용", "${costVsProfit.toPlainString()}%", null)
        }
        return out
    }
}
