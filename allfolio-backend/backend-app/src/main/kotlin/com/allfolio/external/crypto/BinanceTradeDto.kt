package com.allfolio.external.crypto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Binance GET /api/v3/myTrades 응답 DTO
 *
 * Anti-Corruption Layer: Binance 네이밍이 도메인에 유출되지 않도록 이 클래스에서만 사용.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BinanceTradeDto(
    val id: Long,
    val symbol: String,
    val price: String,
    val qty: String,
    val quoteQty: String,
    val commission: String,
    val commissionAsset: String,
    val time: Long,
    val isBuyer: Boolean,
    val isMaker: Boolean,
    val isBestMatch: Boolean,
)
