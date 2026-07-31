package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.CostLedgerSource
import com.allfolio.unifiedasset.application.port.CostRecord
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * R-04 비용 보고서 생성 엔진 (R1 #39 BE).
 * ua_stock_trades 비-DIVIDEND 거래의 fee(매매수수료)·tax(거래세)를 브로커/유형/월별 집계.
 * 비용률·TER·수익대비는 #33 수익률 엔진을 runCatching으로 감싸 null-safe.
 * DIVIDEND 제외(R-03 원천징수 이중집계 방지). 거래 0건은 예외 없는 유효 0 보고서.
 * 사실형 인사이트 포함(조언 아님). 후속: 개인화 룰·bp 벤치마크.
 */
@Component
class CostReportGenerator(
    private val costLedger: CostLedgerSource,
    private val returnsAnalysis: GetReturnsAnalysisUseCase,
) : ReportBodyGenerator {

    override val type = ReportType.COST

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val records = costLedger.findCosts(userId, period.start, period.end)

        val brokerFee = records.sum { it.fee }
        val tradingTax = records.sum { it.tax }
        val totalCost = brokerFee + tradingTax

        val analysis = runCatching { returnsAnalysis.analyze(userId, period.start, period.end) }
            .onFailure { log.debug("비용률 산출용 수익률 분석 실패(비용률·수익대비 생략): {}", it.message) }
            .getOrNull()
        val avgNav: BigDecimal? = analysis?.navSeries?.takeIf { it.isNotEmpty() }
            ?.let { s -> s.fold(BigDecimal.ZERO) { a, p -> a + p.nav }.divide(BigDecimal(s.size), mc) }
        val pnl: BigDecimal? = analysis?.summary?.investmentPnl

        val costRatio: BigDecimal? =
            if (avgNav != null && avgNav > BigDecimal.ZERO) pct(totalCost, avgNav) else null
        val days = ChronoUnit.DAYS.between(period.start, period.end) + 1
        val ter: BigDecimal? =
            costRatio?.multiply(BigDecimal(365))?.divide(BigDecimal(days), 2, RoundingMode.HALF_UP)
        val costVsProfit: BigDecimal? =
            if (pnl != null && pnl.signum() != 0) pct(totalCost, pnl.abs()) else null

        val byType = listOf("매매수수료" to brokerFee, "거래세" to tradingTax)
            .filter { it.second > BigDecimal.ZERO }
            .map { (t, amt) -> mapOf("type" to t, "amount" to amt, "weight" to pct(amt, totalCost)) }

        val byBroker = records.groupBy { it.provider }
            .map { (broker, rs) ->
                val f = rs.sum { it.fee }; val t = rs.sum { it.tax }
                mapOf("broker" to broker, "fee" to f, "tax" to t, "total" to (f + t), "weight" to pct(f + t, totalCost))
            }.sortedByDescending { it["total"] as BigDecimal }

        val monthly = records.groupBy { it.tradeDate.toString().substring(0, 7) }
            .map { (m, rs) ->
                val f = rs.sum { it.fee }; val t = rs.sum { it.tax }
                mapOf("month" to m, "brokerFee" to f, "tradingTax" to t, "total" to (f + t))
            }.sortedBy { it["month"] as String }

        val topBroker = byBroker.firstOrNull()
        val insights = CostInsightCalculator.build(
            totalCost = totalCost, brokerFee = brokerFee, tradingTax = tradingTax,
            costRatio = costRatio, annualizedTer = ter, costVsProfit = costVsProfit,
            topBrokerName = topBroker?.get("broker") as String?,
            topBrokerWeight = topBroker?.get("weight") as BigDecimal?,
        )

        val details = records.map {
            mapOf(
                "date" to it.tradeDate.toString(), "account" to it.accountName, "provider" to it.provider,
                "tradeType" to it.tradeType, "stockName" to it.stockName, "fee" to it.fee, "tax" to it.tax,
            )
        }

        val body = mapOf(
            "summary" to mapOf(
                "totalCost" to totalCost, "brokerFee" to brokerFee, "tradingTax" to tradingTax,
                "tradeCount" to records.size,
                "costRatio" to costRatio, "annualizedTer" to ter, "costVsProfit" to costVsProfit,
                "investmentPnl" to pnl,
            ),
            "byType" to byType,
            "byBroker" to byBroker,
            "monthly" to monthly,
            "details" to details,
            "insights" to insights.map { mapOf("label" to it.label, "value" to it.value, "detail" to it.detail) },
        )
        val asOf = records.maxOfOrNull { it.tradeDate } ?: period.end
        return GeneratedReport(asOfDate = asOf, bodyJson = mapper.writeValueAsString(body))
    }

    private fun List<CostRecord>.sum(sel: (CostRecord) -> BigDecimal): BigDecimal =
        fold(BigDecimal.ZERO) { acc, r -> acc + sel(r) }

    /** a/b × 100, 0~100 스케일 (b<=0이면 0) */
    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)
}
