package com.allfolio.api.trade

import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.domain.TradeType
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class TradeRequest(
    @field:NotNull
    val tenantId: UUID,

    @field:NotNull
    val portfolioId: UUID,

    @field:NotNull
    val assetId: UUID,

    @field:NotNull
    val tradeType: TradeType,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false, message = "quantity must be greater than 0")
    val quantity: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false, message = "price must be greater than 0")
    val price: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0", message = "fee must be non-negative")
    val fee: BigDecimal,

    @field:NotBlank
    val tradeCurrency: String,

    @field:NotNull
    val executedAt: LocalDateTime,
) {
    fun toCommand(): RecordTradeCommand = RecordTradeCommand(
        tenantId      = tenantId,
        portfolioId   = portfolioId,
        assetId       = assetId,
        tradeType     = tradeType,
        quantity      = quantity,
        price         = price,
        fee           = fee,
        tradeCurrency = tradeCurrency,
        executedAt    = executedAt,
    )
}
