package com.allfolio.unifiedasset.domain.asset

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class Asset private constructor(
    val id: UUID,
    val userId: UUID,
    val accountId: UUID,
    val category: AssetCategory,
    val type: AssetType,
    val sourceType: AssetSourceType,
    val name: String,
    val symbol: String?,
    val quantity: BigDecimal,
    val purchasePrice: BigDecimal,
    val currentValue: BigDecimal,
    val currency: String,
    val valuationMethod: ValuationMethod,
    val confidenceLevel: ConfidenceLevel,
    val lastUpdatedAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val memo: String?,
) {
    fun totalPurchaseCost(): BigDecimal = quantity.multiply(purchasePrice)
    fun unrealizedPnl(): BigDecimal = currentValue.subtract(totalPurchaseCost())
    fun returnRate(): BigDecimal {
        val cost = totalPurchaseCost()
        if (cost <= BigDecimal.ZERO) return BigDecimal.ZERO
        return unrealizedPnl().divide(cost, 6, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
    }

    companion object {
        fun create(
            userId: UUID,
            accountId: UUID,
            category: AssetCategory,
            type: AssetType,
            sourceType: AssetSourceType,
            name: String,
            symbol: String?,
            quantity: BigDecimal,
            purchasePrice: BigDecimal,
            currentValue: BigDecimal,
            currency: String,
            valuationMethod: ValuationMethod,
            memo: String? = null,
        ): Asset {
            require(name.isNotBlank()) { "자산명은 필수입니다" }
            require(quantity >= BigDecimal.ZERO) { "수량은 0 이상이어야 합니다" }
            require(currentValue >= BigDecimal.ZERO) { "현재 가치는 0 이상이어야 합니다" }

            val confidence = when (valuationMethod) {
                ValuationMethod.MARKET_PRICE -> ConfidenceLevel.HIGH
                ValuationMethod.BALANCE      -> ConfidenceLevel.HIGH
                ValuationMethod.USER_INPUT   -> ConfidenceLevel.LOW
            }
            val now = LocalDateTime.now()
            return Asset(
                id               = UUID.randomUUID(),
                userId           = userId,
                accountId        = accountId,
                category         = category,
                type             = type,
                sourceType       = sourceType,
                name             = name.trim(),
                symbol           = symbol?.trim()?.uppercase(),
                quantity         = quantity,
                purchasePrice    = purchasePrice,
                currentValue     = currentValue,
                currency         = currency.uppercase(),
                valuationMethod  = valuationMethod,
                confidenceLevel  = confidence,
                lastUpdatedAt    = now,
                createdAt        = now,
                memo             = memo?.trim(),
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, accountId: UUID, category: AssetCategory, type: AssetType,
            sourceType: AssetSourceType, name: String, symbol: String?, quantity: BigDecimal,
            purchasePrice: BigDecimal, currentValue: BigDecimal, currency: String,
            valuationMethod: ValuationMethod, confidenceLevel: ConfidenceLevel,
            lastUpdatedAt: LocalDateTime, createdAt: LocalDateTime, memo: String?,
        ) = Asset(id, userId, accountId, category, type, sourceType, name, symbol,
            quantity, purchasePrice, currentValue, currency, valuationMethod,
            confidenceLevel, lastUpdatedAt, createdAt, memo)
    }
}
