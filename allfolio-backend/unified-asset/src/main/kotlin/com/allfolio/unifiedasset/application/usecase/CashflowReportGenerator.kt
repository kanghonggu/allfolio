package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.CashflowTradeSource
import com.allfolio.unifiedasset.application.port.TradeCashRecord
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
 * v1 제외: 기초/기말 조정·정합검증(월초 잔고 부재), 환전·계좌간이체, 특이거래. 0건은 예외 없는 유효 0 보고서.
 */
@Component
class CashflowReportGenerator(
    private val cashFlowRepository: CashFlowRepository,
    private val tradeSource: CashflowTradeSource,
    private val accountRepository: AccountRepository,
) : ReportBodyGenerator {

    override val type = ReportType.CASHFLOW
    private val mapper = jacksonObjectMapper()

    private val buyTypes = setOf("BUY", "CREDIT_BUY")
    private val sellTypes = setOf("SELL", "CREDIT_SELL")

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
        val netFlow = totalInflow - totalOutflow

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
        val flowRows = flows.map {
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

        val months = (flows.map { ym(it.flowDate) } + trades.map { ym(it.tradeDate) }).distinct().sorted()
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

        val body = mapOf(
            "summary" to mapOf("totalInflow" to totalInflow, "totalOutflow" to totalOutflow, "netFlow" to netFlow),
            "byType" to byType,
            "monthly" to monthly,
            "details" to details,
        )
        val lastDate = (flows.map { it.flowDate } + trades.map { it.tradeDate }).maxOrNull() ?: period.end
        return GeneratedReport(asOfDate = lastDate, bodyJson = mapper.writeValueAsString(body))
    }

    private fun ym(d: LocalDate) = d.toString().substring(0, 7)
}
