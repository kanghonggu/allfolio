package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import kotlin.math.sqrt

/**
 * R-01 월간 운용보고서 생성 엔진 (R1 #36).
 * 성과는 R-02 분석(#33)+BM(#35)을 재사용하고, 보유·익스포저·계좌는 생성 시점
 * ua_assets 상태를 본문에 고정한다(as-of 보존은 아카이브 프레임 담당).
 *
 * v1 제외(데이터 부재): 기여·저해 종목, BM 오버/언더웨이트, 국가·섹터 익스포저, 계좌별 수익률.
 */
@Component
class MonthlyReportGenerator(
    private val returnsAnalysis: GetReturnsAnalysisUseCase,
    private val assetRepository: AssetRepository,
    private val accountRepository: AccountRepository,
    private val fx: FxConverter,
) : ReportBodyGenerator {

    override val type = ReportType.MONTHLY_REPORT

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)
    private val earliest: LocalDate = LocalDate.of(2000, 1, 1)

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        // 월간 성과 — 관측 부족 시 InsufficientDataException 전파 (프레임 관례 400)
        val month = returnsAnalysis.analyze(userId, period.start, period.end)

        val standardFrom = mapOf(
            "3M" to period.end.minusMonths(3),
            "YTD" to period.end.withDayOfYear(1),
            "1Y" to period.end.minusYears(1),
            "SI" to earliest,
        )
        val standard = standardFrom.mapNotNull { (label, from) ->
            runCatching { returnsAnalysis.analyze(userId, from, period.end) }
                .getOrNull()?.let { label to mapOf("twr" to it.summary.twr) }
        }.toMap()

        val assets = assetRepository.findByUserId(userId)
        val valued = assets.map { it to it.currentValueInKrw(fx) }
        val totalKrw = valued.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }

        val topHoldings = valued.sortedByDescending { it.second }.take(10).map { (asset, valueKrw) ->
            mapOf(
                "name" to asset.name,
                "symbol" to asset.symbol,
                "type" to asset.type.name,
                "quantity" to asset.quantity,
                "valueKrw" to valueKrw,
                "weight" to weightPct(valueKrw, totalKrw),
                "returnRate" to asset.returnRate(),
            )
        }

        val byType = valued.groupBy { it.first.type }.map { (type, group) ->
            val sum = group.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
            mapOf("type" to type.name, "valueKrw" to sum, "weight" to weightPct(sum, totalKrw))
        }.sortedByDescending { it["valueKrw"] as BigDecimal }

        val byCurrency = valued.groupBy { it.first.currency }.map { (currency, group) ->
            val sum = group.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
            mapOf("currency" to currency, "valueKrw" to sum, "weight" to weightPct(sum, totalKrw))
        }.sortedByDescending { it["valueKrw"] as BigDecimal }

        val assetsByAccount = valued.groupBy { it.first.accountId }
        val accounts = accountRepository.findByUserId(userId).mapNotNull { account ->
            val group = assetsByAccount[account.id] ?: return@mapNotNull null
            val sum = group.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
            mapOf(
                "accountName" to account.accountName,
                "provider" to account.provider.name,
                "valueKrw" to sum,
                "weight" to weightPct(sum, totalKrw),
                "assetCount" to group.size,
            )
        }.sortedByDescending { it["valueKrw"] as BigDecimal }

        val body = mapOf(
            "performance" to mapOf(
                "month" to mapOf(
                    "twr" to month.summary.twr,
                    "mwr" to month.summary.mwr,
                    "startNav" to month.summary.startNav,
                    "endNav" to month.summary.endNav,
                    "netFlow" to month.summary.netFlow,
                    "investmentPnl" to month.summary.investmentPnl,
                    "benchmark" to month.benchmark?.let {
                        mapOf(
                            "indexType" to it.indexType,
                            "label" to it.label,
                            "periodReturn" to it.periodReturn,
                            "excessReturn" to it.excessReturn,
                        )
                    },
                ),
                "standard" to standard,
                "volatility" to annualizedVolatility(month.navSeries),
            ),
            "topHoldings" to topHoldings,
            "exposure" to mapOf("byType" to byType, "byCurrency" to byCurrency),
            "accounts" to accounts,
            "flowDecomposition" to mapOf(
                "startNav" to month.summary.startNav,
                "netFlow" to month.summary.netFlow,
                "investmentPnl" to month.summary.investmentPnl,
                "endNav" to month.summary.endNav,
            ),
            "note" to "보유·익스포저·계좌 섹션은 보고서 생성 시점 보유 기준",
        )
        return GeneratedReport(asOfDate = month.asOfDate, bodyJson = mapper.writeValueAsString(body))
    }

    private fun weightPct(value: BigDecimal, total: BigDecimal): BigDecimal =
        if (total <= BigDecimal.ZERO) BigDecimal.ZERO
        else value.divide(total, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)

    /** 월간 NAV 구간 수익률 표준편차 × √252 (관측 3건 미만이면 null) */
    private fun annualizedVolatility(series: List<NavPoint>): BigDecimal? {
        if (series.size < 3) return null
        val returns = series.zipWithNext().mapNotNull { (prev, cur) ->
            if (prev.nav <= BigDecimal.ZERO) null
            else cur.nav.divide(prev.nav, mc).toDouble() - 1.0
        }
        if (returns.size < 2) return null
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        val annualized = sqrt(variance) * sqrt(252.0)
        if (annualized.isNaN() || annualized.isInfinite()) return null
        return BigDecimal(annualized, MathContext(6, RoundingMode.HALF_UP))
    }
}
