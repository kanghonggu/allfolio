package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class InternalFlowEntry(
    val date: LocalDate,
    val kind: String,                 // "계좌간이체" | "환전"
    val fromAccount: String?,         // 이체
    val toAccount: String?,           // 이체
    val fromCurrency: String?,        // 환전
    val toCurrency: String?,          // 환전
    val fromAmount: BigDecimal?,      // 환전 원통화 금액
    val toAmount: BigDecimal?,        // 환전 대상통화 금액
    val amountKrw: BigDecimal,        // OUT 레그 KRW 환산
)

/** R-06 Phase 2: period flows 중 내부이동(이체/환전)을 linkId로 페어 그룹핑해 리포트 전용 섹션으로 집계. */
object InternalFlowCalculator {
    fun build(flows: List<CashFlow>, acctNames: Map<UUID, String>): List<InternalFlowEntry> {
        return flows.filter { it.type.isInternal() && it.linkId != null }
            .groupBy { it.linkId!! }
            .mapNotNull { (_, legs) ->
                val out = legs.firstOrNull { it.type == FlowType.TRANSFER_OUT || it.type == FlowType.FX_OUT }
                val inn = legs.firstOrNull { it.type == FlowType.TRANSFER_IN || it.type == FlowType.FX_IN }
                if (out == null || inn == null) return@mapNotNull null   // 페어 미완성 스킵
                if (out.type == FlowType.TRANSFER_OUT) {
                    InternalFlowEntry(
                        date = out.flowDate, kind = "계좌간이체",
                        fromAccount = out.accountId?.let { acctNames[it] } ?: "-",
                        toAccount = inn.accountId?.let { acctNames[it] } ?: "-",
                        fromCurrency = null, toCurrency = null, fromAmount = null, toAmount = null,
                        amountKrw = out.amountKrw,
                    )
                } else {
                    InternalFlowEntry(
                        date = out.flowDate, kind = "환전",
                        fromAccount = null, toAccount = null,
                        fromCurrency = out.currency, toCurrency = inn.currency,
                        fromAmount = out.amount, toAmount = inn.amount,
                        amountKrw = out.amountKrw,
                    )
                }
            }
            .sortedByDescending { it.date }
    }
}
