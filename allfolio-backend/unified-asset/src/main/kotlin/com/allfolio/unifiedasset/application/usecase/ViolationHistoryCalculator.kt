package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import java.math.BigDecimal
import java.time.LocalDate

data class SymbolViolationInfo(val firstBuyDate: LocalDate?, val sinceListed: String)
data class ViolationEvent(val date: LocalDate, val symbol: String, val name: String, val event: String, val note: String)
data class ViolationHistory(val perSymbol: Map<String, SymbolViolationInfo>, val events: List<ViolationEvent>)

/**
 * 배제소스 심볼별 위반 이력 산출 (순수).
 * 편입(qty 0→+)·청산(qty +→0)은 ua_stock_trades 수량추적, 리스트등록은 유저리스트 added_at.
 * 거래·등록일이 전혀 없는 심볼은 제외. period.end 이후 거래 제외. DIVIDEND/MARGIN 무시.
 */
object ViolationHistoryCalculator {
    private val BUY = setOf(StockTradeType.BUY, StockTradeType.CREDIT_BUY)
    private val SELL = setOf(StockTradeType.SELL, StockTradeType.CREDIT_SELL)

    fun build(
        sourceSymbols: Set<String>,
        trades: List<StockTrade>,
        listedAtBySymbol: Map<String, LocalDate>,
        nameBySymbol: Map<String, String>,
        period: ReportPeriod,
    ): ViolationHistory {
        val events = mutableListOf<ViolationEvent>()
        val perSymbol = mutableMapOf<String, SymbolViolationInfo>()

        for (sym in sourceSymbols) {
            val symTrades = trades
                .filter { it.symbol == sym && !it.tradedAt.isAfter(period.end) && (it.tradeType in BUY || it.tradeType in SELL) }
                .sortedWith(compareBy({ it.tradedAt }, { it.createdAt }))
            val listedAt = listedAtBySymbol[sym]
            if (symTrades.isEmpty() && listedAt == null) continue
            val name = nameBySymbol[sym] ?: sym

            var qty = BigDecimal.ZERO
            var firstBuy: LocalDate? = null
            for (tr in symTrades) {
                val before = qty
                qty = if (tr.tradeType in BUY) qty + tr.quantity else qty - tr.quantity
                if (before.signum() <= 0 && qty.signum() > 0) {
                    if (firstBuy == null) firstBuy = tr.tradedAt
                    events += ViolationEvent(tr.tradedAt, sym, name, "편입", "신규 매수")
                }
                if (before.signum() > 0 && qty.signum() <= 0) {
                    events += ViolationEvent(tr.tradedAt, sym, name, "청산", "전량 매도")
                }
            }
            if (listedAt != null && !listedAt.isAfter(period.end)) {
                events += ViolationEvent(listedAt, sym, name, "리스트등록", "배제리스트 추가")
            }

            val sinceListed = when {
                listedAt == null -> "프리셋"
                firstBuy == null -> "-"
                firstBuy.isBefore(listedAt) -> "등록전보유"
                else -> "등록후매수"
            }
            perSymbol[sym] = SymbolViolationInfo(firstBuy, sinceListed)
        }

        // 동일 날짜 다중 교차(당일 매수→매도→재매수)의 시간순 보존 위해 생성순 index를 tie-breaker로 사용
        // (event 문자열 정렬은 청산이 편입보다 앞서는 오류 유발). 생성순은 (tradedAt, createdAt) 오름차순.
        val ordered = events.withIndex()
            .sortedWith(compareBy({ it.value.date }, { it.value.symbol }, { it.index }))
            .map { it.value }
        return ViolationHistory(perSymbol, ordered)
    }
}
