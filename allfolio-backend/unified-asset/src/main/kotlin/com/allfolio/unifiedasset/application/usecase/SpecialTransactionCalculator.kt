package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class SpecialMovement(val date: LocalDate, val account: String, val type: String, val description: String, val amountKrw: BigDecimal)
data class UnclassifiedFlow(val date: LocalDate, val account: String, val tradeType: String, val amountKrw: BigDecimal)
data class SpecialTransactions(val largeMovements: List<SpecialMovement>, val unclassified: List<UnclassifiedFlow>)

/**
 * 특이거래 산출 (순수). 대규모 이동(|금액| ≥ 총자산×ratio) + 미분류 흐름(미매핑 거래유형).
 * 총자산 0이면 대규모 이동 생략. 미결제·환전/이체는 데이터 부재로 범위 밖.
 */
object SpecialTransactionCalculator {
    private val BUY = setOf("BUY", "CREDIT_BUY")
    private val SELL = setOf("SELL", "CREDIT_SELL")
    private val KNOWN = BUY + SELL + setOf("DIVIDEND")

    fun build(
        flows: List<CashFlow>,
        trades: List<TradeCashRecord>,
        acctNames: Map<UUID, String>,
        totalAssetsKrw: BigDecimal,
        thresholdRatio: BigDecimal = BigDecimal("0.10"),
    ): SpecialTransactions {
        val threshold = totalAssetsKrw.multiply(thresholdRatio)
        val large = mutableListOf<SpecialMovement>()
        if (threshold.signum() > 0) {
            flows.forEach { f ->
                if (f.amountKrw.abs() >= threshold) {
                    val acct = f.accountId?.let { acctNames[it] } ?: "-"
                    val (type, amt) = if (f.type == FlowType.DEPOSIT) "입금" to f.amountKrw else "출금" to f.amountKrw.negate()
                    large += SpecialMovement(f.flowDate, acct, type, f.memo ?: type, amt)
                }
            }
            trades.forEach { t ->
                if (t.totalAmount.abs() >= threshold) {
                    val (type, amt) = when {
                        t.tradeType in BUY -> "매수대금" to t.totalAmount.negate()
                        t.tradeType in SELL -> "매도대금" to t.totalAmount
                        t.tradeType == "DIVIDEND" -> "배당·이자" to t.totalAmount
                        else -> "기타" to t.totalAmount
                    }
                    large += SpecialMovement(t.tradeDate, t.accountName, type, t.stockName, amt)
                }
            }
        }
        val unclassified = trades.filter { it.tradeType !in KNOWN }
            .map { UnclassifiedFlow(it.tradeDate, it.accountName, it.tradeType, it.totalAmount) }
        return SpecialTransactions(
            large.sortedByDescending { it.amountKrw.abs() },
            unclassified.sortedBy { it.date },
        )
    }
}
