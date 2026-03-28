package com.allfolio.trade.domain

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * TradeRaw — 불변 거래 원장 (Source of Truth)
 *
 * 설계 원칙:
 * - 생성 후 절대 수정 금지
 * - 삭제 금지
 * - 모든 Snapshot은 이 레코드 기반으로 재계산 가능
 */
class TradeRaw private constructor(
    val id: TradeId,
    val portfolioId: UUID,
    val assetId: UUID,
    val tradeType: TradeType,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val fee: BigDecimal,
    val tradeCurrency: String,
    val executedAt: LocalDateTime,
    val createdAt: LocalDateTime,
) {
    /**
     * 거래 총액 (수수료 미포함)
     * gross = quantity × price
     */
    fun grossAmount(): BigDecimal = quantity.multiply(price)

    /**
     * 순 현금 흐름 (포트폴리오 관점)
     * BUY  → 현금 출금: -(gross + fee)
     * SELL → 현금 입금: +(gross - fee)
     */
    fun netAmount(): BigDecimal = when (tradeType) {
        TradeType.BUY  -> grossAmount().add(fee).negate()
        TradeType.SELL -> grossAmount().subtract(fee)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TradeRaw) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "TradeRaw(id=$id, portfolioId=$portfolioId, assetId=$assetId, " +
        "type=$tradeType, qty=$quantity, price=$price, fee=$fee, " +
        "currency=$tradeCurrency, executedAt=$executedAt)"

    companion object {
        fun create(
            portfolioId: UUID,
            assetId: UUID,
            tradeType: TradeType,
            quantity: BigDecimal,
            price: BigDecimal,
            fee: BigDecimal,
            tradeCurrency: String,
            executedAt: LocalDateTime,
        ): TradeRaw {
            if (quantity <= BigDecimal.ZERO) throw TradeException.invalidQuantity(quantity)
            if (price <= BigDecimal.ZERO)    throw TradeException.invalidPrice(price)
            if (fee < BigDecimal.ZERO)       throw TradeException.negativeFee(fee)
            if (tradeCurrency.isBlank())     throw TradeException.blankCurrency()

            return TradeRaw(
                id             = TradeId.newId(),
                portfolioId    = portfolioId,
                assetId        = assetId,
                tradeType      = tradeType,
                quantity       = quantity,
                price          = price,
                fee            = fee,
                tradeCurrency  = tradeCurrency.uppercase(),
                executedAt     = executedAt,
                createdAt      = LocalDateTime.now(),
            )
        }

        fun reconstruct(
            id: TradeId,
            portfolioId: UUID,
            assetId: UUID,
            tradeType: TradeType,
            quantity: BigDecimal,
            price: BigDecimal,
            fee: BigDecimal,
            tradeCurrency: String,
            executedAt: LocalDateTime,
            createdAt: LocalDateTime,
        ): TradeRaw = TradeRaw(
            id            = id,
            portfolioId   = portfolioId,
            assetId       = assetId,
            tradeType     = tradeType,
            quantity      = quantity,
            price         = price,
            fee           = fee,
            tradeCurrency = tradeCurrency,
            executedAt    = executedAt,
            createdAt     = createdAt,
        )
    }
}
