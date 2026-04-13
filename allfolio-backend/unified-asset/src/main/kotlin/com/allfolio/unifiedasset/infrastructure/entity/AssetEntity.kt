package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.asset.*
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_assets")
class AssetEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val category: AssetCategory,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: AssetType,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    val sourceType: AssetSourceType,

    @Column(nullable = false)
    val name: String,

    @Column(length = 20)
    val symbol: String?,

    @Column(nullable = false, precision = 30, scale = 10)
    val quantity: BigDecimal,

    @Column(name = "purchase_price", nullable = false, precision = 30, scale = 10)
    val purchasePrice: BigDecimal,

    @Column(name = "current_value", nullable = false, precision = 30, scale = 10)
    val currentValue: BigDecimal,

    @Column(nullable = false, length = 10)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_method", nullable = false, length = 20)
    val valuationMethod: ValuationMethod,

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level", nullable = false, length = 10)
    val confidenceLevel: ConfidenceLevel,

    @Column(name = "last_updated_at", nullable = false)
    val lastUpdatedAt: LocalDateTime,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(length = 500)
    val memo: String?,
) {
    fun toDomain() = Asset.reconstruct(
        id, userId, accountId, category, type, sourceType, name, symbol,
        quantity, purchasePrice, currentValue, currency, valuationMethod,
        confidenceLevel, lastUpdatedAt, createdAt, memo
    )

    companion object {
        fun fromDomain(a: Asset) = AssetEntity(
            a.id, a.userId, a.accountId, a.category, a.type, a.sourceType,
            a.name, a.symbol, a.quantity, a.purchasePrice, a.currentValue,
            a.currency, a.valuationMethod, a.confidenceLevel,
            a.lastUpdatedAt, a.createdAt, a.memo
        )
    }
}
