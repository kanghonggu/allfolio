package com.allfolio.api.portfolio

import com.allfolio.report.domain.returns.Flow
import com.allfolio.trade.domain.TradeType
import com.allfolio.trade.infrastructure.entity.TradeRawEntity
import java.math.BigDecimal

/**
 * 거래 파이프라인(trade_raw) 포트폴리오의 외부 플로우 = **매매 대금**.
 *
 * 이 포트폴리오의 NAV는 `Σ 수량 × 시장가`이고 현금 계정이 없다(DailySnapshotOrchestrator).
 * 그래서 매수는 밖에서 돈이 들어와 NAV가 느는 것과 같고, 매도 대금은 NAV 밖으로 나간다.
 * 이걸 플로우로 빼지 않으면 매수일이 수익, 매도일이 손실로 잡힌다 — 통합자산의 입금·출금과
 * 같은 오염이다. 통합자산의 cash_flows는 사용자 단위라 이 포트폴리오에 쓰면 안 된다.
 *
 * - BUY  → +(수량 × 체결가 + 수수료): 낸 돈. 수수료는 NAV에 안 잡히므로 그날의 손실로 나온다.
 * - SELL → −(수량 × 체결가 − 수수료): 받은 돈.
 * - 날짜는 `executedAt.toLocalDate()` — 스냅샷 트리거가 하루를 자르는 기준과 같다.
 *
 * 환산은 호출자가 주는 [toKrw]로 한다. 트리거가 NAV를 만들 때도 지금 환율로 환산하므로
 * (SnapshotTriggerService) 외화 거래는 환율이 움직인 만큼 오차가 남는다. KRW 거래는 정확하다.
 */
object TradeFlows {

    fun of(trades: List<TradeRawEntity>, toKrw: (BigDecimal, String) -> BigDecimal): List<Flow> =
        trades.groupBy { it.executedAt.toLocalDate() }
            .map { (date, dayTrades) ->
                Flow(date, dayTrades.fold(BigDecimal.ZERO) { acc, t -> acc + toKrw(cashIn(t), t.tradeCurrency) })
            }
            .sortedBy { it.date }

    /** 포트폴리오로 들어간 돈(원통화). 매도는 음수 */
    private fun cashIn(t: TradeRawEntity): BigDecimal {
        val gross = t.quantity.multiply(t.price)
        return when (t.tradeType) {
            TradeType.BUY -> gross + t.fee
            TradeType.SELL -> (gross - t.fee).negate()
        }
    }
}
