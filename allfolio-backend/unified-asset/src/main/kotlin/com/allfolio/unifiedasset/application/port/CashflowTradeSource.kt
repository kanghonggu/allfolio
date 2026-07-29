package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 현금흐름 분류용 주식 거래 1건 — 금액은 KRW 취급. */
data class TradeCashRecord(
    val tradeDate: LocalDate,
    val tradeType: String,
    val stockName: String,
    val accountName: String,
    val totalAmount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
)

interface CashflowTradeSource {
    /** [from, to] 구간의 모든 주식 거래 (거래일 오름차순) */
    fun findTrades(userId: UUID, from: LocalDate, to: LocalDate): List<TradeCashRecord>
}
