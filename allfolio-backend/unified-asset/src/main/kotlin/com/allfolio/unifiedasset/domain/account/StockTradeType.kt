package com.allfolio.unifiedasset.domain.account

enum class StockTradeType {
    BUY,          // 매수
    SELL,         // 매도
    CREDIT_BUY,   // 신용매수
    CREDIT_SELL,  // 신용매도
    MARGIN,       // 미수
    DIVIDEND,     // 배당
}
