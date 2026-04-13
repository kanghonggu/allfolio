package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_stock_trades")
class StockTradeEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_type", nullable = false, length = 20)
    val tradeType: StockTradeType,

    @Column(name = "stock_name", nullable = false)
    val stockName: String,

    @Column(length = 20)
    val symbol: String?,

    @Column(nullable = false, precision = 20, scale = 4)
    val quantity: BigDecimal,

    @Column(nullable = false, precision = 20, scale = 4)
    val price: BigDecimal,

    @Column(name = "total_amount", nullable = false, precision = 20, scale = 4)
    val totalAmount: BigDecimal,

    @Column(nullable = false, precision = 20, scale = 4)
    val fee: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false, precision = 20, scale = 4)
    val tax: BigDecimal = BigDecimal.ZERO,

    @Column(name = "traded_at", nullable = false)
    val tradedAt: LocalDate,

    @Column(length = 500)
    val memo: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    fun toDomain() = StockTrade.reconstruct(
        id, accountId, userId, tradeType, stockName, symbol, quantity, price,
        totalAmount, fee, tax, tradedAt, memo, createdAt,
    )

    companion object {
        fun fromDomain(t: StockTrade) = StockTradeEntity(
            t.id, t.accountId, t.userId, t.tradeType, t.stockName, t.symbol,
            t.quantity, t.price, t.totalAmount, t.fee, t.tax, t.tradedAt, t.memo, t.createdAt,
        )
    }
}
