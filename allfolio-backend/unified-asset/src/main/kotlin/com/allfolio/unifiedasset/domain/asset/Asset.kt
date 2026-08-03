package com.allfolio.unifiedasset.domain.asset

import java.math.BigDecimal
import java.time.LocalDate
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
    val subType: String?,
    val loanAmount: BigDecimal?,
    val maturityDate: LocalDate?,
    val liquidityType: AssetLiquidityType,
    val areaPyeong: BigDecimal?,
) {
    // ILLIQUID(부동산·차량 등): purchasePrice가 총액이므로 수량을 곱하지 않음
    fun totalPurchaseCost(): BigDecimal =
        if (liquidityType == AssetLiquidityType.ILLIQUID) purchasePrice
        else quantity.multiply(purchasePrice)

    fun unrealizedPnl(): BigDecimal = currentValue.subtract(totalPurchaseCost())
    fun returnRate(): BigDecimal {
        val cost = totalPurchaseCost()
        if (cost <= BigDecimal.ZERO) return BigDecimal.ZERO
        return unrealizedPnl().divide(cost, 6, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
    }
    fun netEquity(): BigDecimal = currentValue.subtract(loanAmount ?: BigDecimal.ZERO)

    companion object {
        private val ILLIQUID_TYPES = setOf(
            AssetType.REAL_ESTATE, AssetType.JEONSE, AssetType.VEHICLE,
        )

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
            subType: String? = null,
            loanAmount: BigDecimal? = null,
            maturityDate: LocalDate? = null,
            areaPyeong: BigDecimal? = null,
        ): Asset {
            // QA P2: 자산명 서버 사이드 산티타이징 + 통화 화이트리스트 검증
            val safeName = com.allfolio.unifiedasset.domain.common.sanitizeUserText(name)
            require(safeName.isNotBlank()) { "자산명은 필수입니다" }
            require(quantity >= BigDecimal.ZERO) { "수량은 0 이상이어야 합니다" }
            require(currentValue >= BigDecimal.ZERO) { "현재 가치는 0 이상이어야 합니다" }

            val confidence = when (valuationMethod) {
                ValuationMethod.MARKET_PRICE -> ConfidenceLevel.HIGH
                ValuationMethod.BALANCE      -> ConfidenceLevel.HIGH
                ValuationMethod.USER_INPUT   -> ConfidenceLevel.LOW
            }
            val now = LocalDateTime.now()
            return Asset(
                id              = UUID.randomUUID(),
                userId          = userId,
                accountId       = accountId,
                category        = category,
                type            = type,
                sourceType      = sourceType,
                name            = safeName,
                symbol          = symbol?.trim()
                    ?.let { if (type == AssetType.CRYPTO || type == AssetType.STOCK) it.uppercase() else it },
                quantity        = quantity,
                purchasePrice   = purchasePrice,
                currentValue    = currentValue,
                currency        = com.allfolio.unifiedasset.domain.common.Currencies.normalize(currency),
                valuationMethod = valuationMethod,
                confidenceLevel = confidence,
                lastUpdatedAt   = now,
                createdAt       = now,
                memo            = memo?.trim(),
                subType         = subType?.trim()?.uppercase(),
                loanAmount      = loanAmount,
                maturityDate    = maturityDate,
                liquidityType   = if (type in ILLIQUID_TYPES) AssetLiquidityType.ILLIQUID
                                  else AssetLiquidityType.LIQUID,
                areaPyeong      = areaPyeong,
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, accountId: UUID, category: AssetCategory, type: AssetType,
            sourceType: AssetSourceType, name: String, symbol: String?, quantity: BigDecimal,
            purchasePrice: BigDecimal, currentValue: BigDecimal, currency: String,
            valuationMethod: ValuationMethod, confidenceLevel: ConfidenceLevel,
            lastUpdatedAt: LocalDateTime, createdAt: LocalDateTime, memo: String?,
            subType: String? = null, loanAmount: BigDecimal? = null,
            maturityDate: LocalDate? = null,
            liquidityType: AssetLiquidityType = AssetLiquidityType.LIQUID,
            areaPyeong: BigDecimal? = null,
        ) = Asset(
            id, userId, accountId, category, type, sourceType, name, symbol,
            quantity, purchasePrice, currentValue, currency, valuationMethod,
            confidenceLevel, lastUpdatedAt, createdAt, memo, subType, loanAmount,
            maturityDate, liquidityType, areaPyeong,
        )
    }
}
