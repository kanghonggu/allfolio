package com.allfolio.unifiedasset.domain.account

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class StockTrade private constructor(
    val id: UUID,
    val accountId: UUID,
    val userId: UUID,
    val tradeType: StockTradeType,
    val stockName: String,
    val symbol: String?,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val totalAmount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
    val tradedAt: LocalDate,
    val memo: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun create(
            accountId: UUID,
            userId: UUID,
            tradeType: StockTradeType,
            stockName: String,
            symbol: String?,
            quantity: BigDecimal,
            price: BigDecimal,
            totalAmount: BigDecimal,
            fee: BigDecimal = BigDecimal.ZERO,
            tax: BigDecimal = BigDecimal.ZERO,
            tradedAt: LocalDate,
            memo: String?,
        ) = StockTrade(
            id          = UUID.randomUUID(),
            accountId   = accountId,
            userId      = userId,
            tradeType   = tradeType,
            stockName   = stockName.trim(),
            symbol      = symbol?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
            quantity    = quantity,
            price       = price,
            totalAmount = totalAmount,
            fee         = fee,
            tax         = tax,
            tradedAt    = tradedAt,
            memo        = memo?.trim()?.takeIf { it.isNotBlank() },
            createdAt   = LocalDateTime.now(),
        )

        fun reconstruct(
            id: UUID, accountId: UUID, userId: UUID, tradeType: StockTradeType,
            stockName: String, symbol: String?, quantity: BigDecimal, price: BigDecimal,
            totalAmount: BigDecimal, fee: BigDecimal, tax: BigDecimal,
            tradedAt: LocalDate, memo: String?, createdAt: LocalDateTime,
        ) = StockTrade(id, accountId, userId, tradeType, stockName, symbol, quantity, price,
            totalAmount, fee, tax, tradedAt, memo, createdAt)
    }
}
