package com.allfolio.trade.domain

import com.allfolio.common.domain.DomainException
import java.math.BigDecimal

class TradeException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : DomainException(errorCode, message, cause) {

    companion object {
        fun notFound(id: TradeId) =
            TradeException("TRADE_NOT_FOUND", "Trade not found: $id")

        fun invalidQuantity(quantity: BigDecimal) =
            TradeException("TRADE_INVALID_QUANTITY", "Quantity must be greater than 0: $quantity")

        fun invalidPrice(price: BigDecimal) =
            TradeException("TRADE_INVALID_PRICE", "Price must be greater than 0: $price")

        fun negativeFee(fee: BigDecimal) =
            TradeException("TRADE_NEGATIVE_FEE", "Fee must be non-negative: $fee")

        fun blankCurrency() =
            TradeException("TRADE_BLANK_CURRENCY", "Trade currency must not be blank")
    }
}
