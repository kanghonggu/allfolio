package com.allfolio.trade.domain

import java.math.BigDecimal

/** 원가 계산 Lot — BUY 1건 = Lot 1개. 불변. */
data class CostLot(
    val unitPrice: BigDecimal,
    val quantity: BigDecimal,
)
