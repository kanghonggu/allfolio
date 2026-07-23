package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 비용 거래 1건 — 금액은 KRW 취급(ua_stock_trades에 통화 컬럼 없음). DIVIDEND 제외(R-03 전담). */
data class CostRecord(
    val tradeDate: LocalDate,
    val stockName: String,
    val symbol: String?,
    val accountName: String,
    val provider: String,
    val tradeType: String,
    val fee: BigDecimal,   // 매매수수료
    val tax: BigDecimal,   // 거래세·제세금
) {
    val total: BigDecimal get() = fee + tax
}

interface CostLedgerSource {
    /** [from, to] 구간의 비-DIVIDEND 거래 비용 (거래일 오름차순) */
    fun findCosts(userId: UUID, from: LocalDate, to: LocalDate): List<CostRecord>
}
