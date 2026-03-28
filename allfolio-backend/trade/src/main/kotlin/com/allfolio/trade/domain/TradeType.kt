package com.allfolio.trade.domain

enum class TradeType {
    BUY,
    SELL,
    ;

    fun isBuy(): Boolean = this == BUY

    fun isSell(): Boolean = this == SELL
}
