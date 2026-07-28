package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 배당 수취 1건 — 금액은 KRW 취급(ua_stock_trades에 통화 컬럼 없음). */
data class DividendRecord(
    val payDate: LocalDate,
    val stockName: String,
    val symbol: String?,
    val accountName: String,
    val provider: String,
    val gross: BigDecimal,   // 세전 (total_amount)
    val tax: BigDecimal,     // 원천징수
) {
    val net: BigDecimal get() = gross - tax
}

interface DividendLedgerSource {
    /** [from, to] 구간의 배당 수취 기록 (지급일 오름차순) */
    fun findDividends(userId: UUID, from: LocalDate, to: LocalDate): List<DividendRecord>
}
