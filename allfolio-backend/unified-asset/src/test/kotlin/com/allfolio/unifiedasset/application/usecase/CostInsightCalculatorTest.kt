package com.allfolio.unifiedasset.application.usecase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CostInsightCalculatorTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun `TER는 bp로 환산되고 costRatio-투자손익대비-최대비용처-비용구성이 모두 포함된다`() {
        val r = CostInsightCalculator.build(
            totalCost = bd("10000"), brokerFee = bd("7000"), tradingTax = bd("3000"),
            costRatio = bd("0.15"), annualizedTer = bd("1.80"), costVsProfit = bd("12.50"),
            topBrokerName = "KIS", topBrokerWeight = bd("60.00"),
        )
        val byLabel = r.associateBy { it.label }
        assertThat(byLabel["연환산 TER"]!!.value).isEqualTo("180bp")
        assertThat(byLabel["기간 비용률"]!!.value).contains("0.15%").contains("15bp")
        assertThat(byLabel["최대 비용 처"]!!.value).isEqualTo("KIS")
        assertThat(byLabel["최대 비용 처"]!!.detail).contains("60.00")
        assertThat(byLabel["비용 구성"]!!.value).contains("매매수수료 70").contains("거래세 30")
        assertThat(byLabel["투자손익 대비 비용"]!!.value).isEqualTo("12.50%")
    }

    @Test
    fun `costRatio-ter-costVsProfit null이면 해당 인사이트는 생략된다`() {
        val r = CostInsightCalculator.build(
            totalCost = bd("10000"), brokerFee = bd("10000"), tradingTax = bd("0"),
            costRatio = null, annualizedTer = null, costVsProfit = null,
            topBrokerName = "KIS", topBrokerWeight = bd("100.00"),
        )
        val labels = r.map { it.label }
        assertThat(labels).doesNotContain("연환산 TER", "기간 비용률", "투자손익 대비 비용")
        assertThat(labels).contains("최대 비용 처", "비용 구성")
    }

    @Test
    fun `topBroker null이거나 totalCost 0이면 최대비용처-비용구성 생략`() {
        val r = CostInsightCalculator.build(
            totalCost = bd("0"), brokerFee = bd("0"), tradingTax = bd("0"),
            costRatio = null, annualizedTer = null, costVsProfit = null,
            topBrokerName = null, topBrokerWeight = null,
        )
        assertThat(r).isEmpty()
    }

    @Test
    fun `순서는 TER-비용률-최대비용처-비용구성-투자손익대비`() {
        val r = CostInsightCalculator.build(
            totalCost = bd("100"), brokerFee = bd("60"), tradingTax = bd("40"),
            costRatio = bd("0.10"), annualizedTer = bd("1.00"), costVsProfit = bd("5.00"),
            topBrokerName = "KIS", topBrokerWeight = bd("100.00"),
        )
        assertThat(r.map { it.label }).containsExactly(
            "연환산 TER", "기간 비용률", "최대 비용 처", "비용 구성", "투자손익 대비 비용",
        )
    }
}
