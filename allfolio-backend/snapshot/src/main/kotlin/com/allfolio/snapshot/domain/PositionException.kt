package com.allfolio.snapshot.domain

import com.allfolio.common.domain.DomainException
import java.math.BigDecimal
import java.util.UUID

class PositionException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : DomainException(errorCode, message, cause) {

    companion object {
        fun insufficientQuantity(assetId: UUID, requested: BigDecimal, available: BigDecimal) =
            PositionException(
                "POSITION_INSUFFICIENT_QUANTITY",
                "Insufficient quantity for asset $assetId: requested=$requested, available=$available",
            )

        fun negativeQuantity(quantity: BigDecimal) =
            PositionException(
                "POSITION_NEGATIVE_QUANTITY",
                "Quantity must be non-negative: $quantity",
            )

        fun emptyTrades() =
            PositionException(
                "POSITION_EMPTY_TRADES",
                "Trade list must not be empty",
            )
    }
}
