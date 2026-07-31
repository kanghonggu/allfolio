package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.CashflowTradeSource
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * R-06 현금흐름 보고서 생성 엔진 (R2 #41 BE).
 * cash_flow(입금/출금) + ua_stock_trades(매수/매도/배당/수수료)를 유형별 분류·집계.
 * 유입: 입금·매도대금·배당·이자 / 유출: 출금·매수대금·수수료·세금. 순흐름 = 유입 − 유출.
 * 기초/기말 현금 조정표·정합검증(전체 이력 재구성) + 특이거래(대규모 이동·미분류) 포함. 후속: 미결제·환전·계좌간이체.
 * 0건은 예외 없는 유효 0 보고서.
 */
@Component
class CashflowReportGenerator(
    private val cashFlowRepository: CashFlowRepository,
    private val tradeSource: CashflowTradeSource,
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
) : ReportBodyGenerator {

    override val type = ReportType.CASHFLOW
    private val mapper = jacksonObjectMapper()

    private val buyTypes = setOf("BUY", "CREDIT_BUY")
    private val sellTypes = setOf("SELL", "CREDIT_SELL")

    /** flows·trades → 순현금이동(KRW): 입금−출금 + 매도−매수 + 배당 − 수수료·세금. */
    private fun netCash(fs: List<CashFlow>, ts: List<TradeCashRecord>): BigDecimal {
        val dep = fs.filter { it.type == FlowType.DEPOSIT }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw }
        val wd = fs.filter { it.type == FlowType.WITHDRAWAL }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw }
        val buy = ts.filter { it.tradeType in buyTypes }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
        val sell = ts.filter { it.tradeType in sellTypes }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
        val div = ts.filter { it.tradeType == "DIVIDEND" }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
        val fees = ts.fold(BigDecimal.ZERO) { a, t -> a + t.fee + t.tax }
        return dep - wd + sell - buy + div - fees
    }

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val flows = cashFlowRepository.findByUserIdAndPeriod(userId, period.start, period.end)
        val trades = tradeSource.findTrades(userId, period.start, period.end)
        val acctNames = accountRepository.findByUserId(userId).associate { it.id to it.accountName }

        fun sumFlow(t: FlowType) = flows.filter { it.type == t }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw }
        fun sumTrade(pred: (TradeCashRecord) -> Boolean) =
            trades.filter(pred).fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }

        val deposit = sumFlow(FlowType.DEPOSIT)
        val withdrawal = sumFlow(FlowType.WITHDRAWAL)
        val buy = sumTrade { it.tradeType in buyTypes }
        val sell = sumTrade { it.tradeType in sellTypes }
        val dividend = sumTrade { it.tradeType == "DIVIDEND" }
        val feesTax = trades.fold(BigDecimal.ZERO) { a, t -> a + t.fee + t.tax }

        val totalInflow = deposit + sell + dividend
        val totalOutflow = withdrawal + buy + feesTax
        val netFlow = netCash(flows, trades)   // 기초 재구성과 동일 공식(단일 소스). == totalInflow − totalOutflow

        val byType = buildList {
            fun row(type: String, amount: BigDecimal, dir: String) =
                mapOf("type" to type, "amount" to amount, "direction" to dir)
            if (deposit.signum() != 0) add(row("입금", deposit, "IN"))
            if (sell.signum() != 0) add(row("매도대금", sell, "IN"))
            if (dividend.signum() != 0) add(row("배당·이자", dividend, "IN"))
            if (withdrawal.signum() != 0) add(row("출금", withdrawal.negate(), "OUT"))
            if (buy.signum() != 0) add(row("매수대금", buy.negate(), "OUT"))
            if (feesTax.signum() != 0) add(row("수수료·세금", feesTax.negate(), "OUT"))
        }

        data class Row(val date: LocalDate, val account: String, val type: String, val desc: String, val amount: BigDecimal)
        val flowRows = flows.filter { !it.type.isInternal() }.map {
            val acct = it.accountId?.let { id -> acctNames[id] } ?: "-"
            if (it.type == FlowType.DEPOSIT) Row(it.flowDate, acct, "입금", it.memo ?: "입금", it.amountKrw)
            else Row(it.flowDate, acct, "출금", it.memo ?: "출금", it.amountKrw.negate())
        }
        val tradeRows = trades.mapNotNull { t ->
            when {
                t.tradeType in buyTypes -> Row(t.tradeDate, t.accountName, "매수대금", t.stockName, t.totalAmount.negate())
                t.tradeType in sellTypes -> Row(t.tradeDate, t.accountName, "매도대금", t.stockName, t.totalAmount)
                t.tradeType == "DIVIDEND" -> Row(t.tradeDate, t.accountName, "배당·이자", t.stockName, t.totalAmount)
                else -> null
            }
        }
        val details = (flowRows + tradeRows).sortedBy { it.date }.map {
            mapOf("date" to it.date.toString(), "account" to it.account, "type" to it.type,
                  "description" to it.desc, "amount" to it.amount)
        }

        val months = (flows.filter { !it.type.isInternal() }.map { ym(it.flowDate) } +
            trades.map { ym(it.tradeDate) }).distinct().sorted()
        val monthly = months.map { m ->
            val mf = flows.filter { ym(it.flowDate) == m }
            val mt = trades.filter { ym(it.tradeDate) == m }
            val inflow = mf.filter { it.type == FlowType.DEPOSIT }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw } +
                mt.filter { it.tradeType in sellTypes }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount } +
                mt.filter { it.tradeType == "DIVIDEND" }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
            val outflow = mf.filter { it.type == FlowType.WITHDRAWAL }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw } +
                mt.filter { it.tradeType in buyTypes }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount } +
                mt.fold(BigDecimal.ZERO) { a, t -> a + t.fee + t.tax }
            mapOf("month" to m, "inflow" to inflow, "outflow" to outflow, "net" to (inflow - outflow))
        }

        val epoch = LocalDate.of(1970, 1, 1)
        val far = LocalDate.of(9999, 12, 31)
        val beforeFlows = cashFlowRepository.findByUserIdAndPeriod(userId, epoch, period.start.minusDays(1))
        val beforeTrades = tradeSource.findTrades(userId, epoch, period.start.minusDays(1))
        val openingBalance = netCash(beforeFlows, beforeTrades)
        val closingCalculated = openingBalance + netFlow
        val assets = assetRepository.findByUserId(userId)
        val totalAssetsKrw = assets.fold(BigDecimal.ZERO) { a, x -> a + x.currentValueInKrw(fx) }
        val actualCash = assets.filter { it.type == AssetType.CASH }.fold(BigDecimal.ZERO) { a, x -> a + x.currentValueInKrw(fx) }
        val afterFlows = cashFlowRepository.findByUserIdAndPeriod(userId, period.end.plusDays(1), far)
        val afterTrades = tradeSource.findTrades(userId, period.end.plusDays(1), far)
        val reconcilable = afterFlows.isEmpty() && afterTrades.isEmpty()
        val difference = actualCash - closingCalculated
        val reconciled = reconcilable && difference.abs() < BigDecimal.ONE

        val special = SpecialTransactionCalculator.build(flows.filter { !it.type.isInternal() }, trades, acctNames, totalAssetsKrw)

        val body = mapOf(
            "summary" to mapOf("totalInflow" to totalInflow, "totalOutflow" to totalOutflow, "netFlow" to netFlow),
            "byType" to byType,
            "monthly" to monthly,
            "details" to details,
            "reconciliation" to mapOf(
                "openingBalance" to openingBalance,
                "changes" to byType,
                "closingCalculated" to closingCalculated,
                "actualCash" to actualCash,
                "difference" to difference,
                "reconcilable" to reconcilable,
                "reconciled" to reconciled,
            ),
            "specialTransactions" to mapOf(
                "thresholdRatio" to BigDecimal("0.10"),
                "largeMovements" to special.largeMovements.map {
                    mapOf("date" to it.date.toString(), "account" to it.account, "type" to it.type,
                        "description" to it.description, "amountKrw" to it.amountKrw)
                },
                "unclassified" to special.unclassified.map {
                    mapOf("date" to it.date.toString(), "account" to it.account, "tradeType" to it.tradeType, "amountKrw" to it.amountKrw)
                },
            ),
        )
        val lastDate = (flows.map { it.flowDate } + trades.map { it.tradeDate }).maxOrNull() ?: period.end
        return GeneratedReport(asOfDate = lastDate, bodyJson = mapper.writeValueAsString(body))
    }

    private fun ym(d: LocalDate) = d.toString().substring(0, 7)
}
