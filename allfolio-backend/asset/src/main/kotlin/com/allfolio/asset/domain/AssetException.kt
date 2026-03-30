package com.allfolio.asset.domain

import com.allfolio.common.domain.DomainException

class AssetException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : DomainException(errorCode, message, cause) {

    companion object {
        fun notFound(id: AssetId) =
            AssetException("ASSET_NOT_FOUND", "Asset not found: $id")

        fun duplicateSymbol(symbol: String) =
            AssetException("ASSET_DUPLICATE_SYMBOL", "Asset symbol already exists: $symbol")

        fun blankSymbol() =
            AssetException("ASSET_BLANK_SYMBOL", "Asset symbol must not be blank")

        fun blankName() =
            AssetException("ASSET_BLANK_NAME", "Asset name must not be blank")

        fun blankNativeCurrency() =
            AssetException("ASSET_BLANK_NATIVE_CURRENCY", "Asset native currency must not be blank")

        fun alreadyInactive(id: AssetId) =
            AssetException("ASSET_ALREADY_INACTIVE", "Asset is already inactive: $id")

        fun alreadyActive(id: AssetId) =
            AssetException("ASSET_ALREADY_ACTIVE", "Asset is already active: $id")
    }
}
