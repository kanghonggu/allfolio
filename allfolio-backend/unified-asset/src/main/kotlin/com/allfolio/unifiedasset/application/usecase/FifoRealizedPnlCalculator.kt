package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.domain.LotPosition
import com.allfolio.trade.domain.TradeType
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import java.math.BigDecimal

/**
 * ua_stock_trades 기반 심볼별 당월 FIFO 실현손익(KRW) 계산 (순수).
 * 검증된 trade 모듈 FifoCostEngine.apply를 재사용한다.
 * period.start 직전 누적 실현손익을 스냅샷해 당월분(= 최종 − 직전)만 반환한다.
 * 통화 컬럼 부재 → KRW 취급. DIVIDEND/MARGIN·symbol 없음은 제외.
 */
object FifoRealizedPnlCalculator {
    private val BUY_TYPES = setOf(StockTradeType.BUY, StockTradeType.CREDIT_BUY)
    private val SELL_TYPES = setOf(StockTradeType.SELL, StockTradeType.CREDIT_SELL)

    fun calculate(trades: List<StockTrade>, period: ReportPeriod): Map<String, BigDecimal> =
        trades
            .filter { it.symbol != null && !it.tradedAt.isAfter(period.end) && (it.tradeType in BUY_TYPES || it.tradeType in SELL_TYPES) }
            .groupBy { it.symbol!! }
            .mapValues { (_, ts) -> monthRealized(ts, period) }

    private fun monthRealized(symbolTrades: List<StockTrade>, period: ReportPeriod): BigDecimal {
        val asc = symbolTrades.sortedWith(compareBy({ it.tradedAt }, { it.createdAt }))
        var pos = LotPosition.EMPTY
        var realizedBeforeStart = BigDecimal.ZERO
        var crossed = false
        for (t in asc) {
            if (!crossed && !t.tradedAt.isBefore(period.start)) {
                realizedBeforeStart = pos.realizedPnl
                crossed = true
            }
            val tt = if (t.tradeType in BUY_TYPES) TradeType.BUY else TradeType.SELL
            pos = FifoCostEngine.apply(pos, tt, t.quantity, t.price, t.fee)
        }
        if (!crossed) realizedBeforeStart = pos.realizedPnl // 전부 기간 이전 → 당월 0
        return pos.realizedPnl - realizedBeforeStart
    }
}
