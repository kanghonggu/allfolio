package com.allfolio.snapshot.domain

import com.allfolio.common.domain.DomainException
import java.util.UUID

class OrchestratorException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : DomainException(errorCode, message, cause) {

    companion object {
        fun missingMarketPrice(assetId: UUID) =
            OrchestratorException(
                "SNAPSHOT_MISSING_MARKET_PRICE",
                "Market price not found for asset: $assetId",
            )
    }
}
