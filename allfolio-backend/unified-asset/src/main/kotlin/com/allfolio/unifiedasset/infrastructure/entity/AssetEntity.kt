package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.asset.*
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
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

    @Column(length = 200)
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

    @Column(name = "sub_type", length = 30)
    val subType: String?,

    @Column(name = "loan_amount", precision = 30, scale = 10)
    val loanAmount: BigDecimal?,

    @Column(name = "maturity_date")
    val maturityDate: LocalDate?,

    @Enumerated(EnumType.STRING)
    @Column(name = "liquidity_type", nullable = false, length = 20)
    val liquidityType: AssetLiquidityType = AssetLiquidityType.LIQUID,

    @Column(name = "area_pyeong", precision = 10, scale = 2)
    val areaPyeong: BigDecimal? = null,

    /**
     * 자동 평가된 자산의 시세 기준일 (A1). 수동 입력 자산은 null이다.
     * **`lastUpdatedAt`과 다르다** — 그쪽은 우리가 쓴 시각, 이쪽은 시세가 언제 것인가.
     */
    @Column(name = "price_as_of")
    val priceAsOf: LocalDate? = null,

    /**
     * 전용면적(㎡) — 실거래가 매칭 키 (A1 v3).
     *
     * **[areaPyeong]과 역할이 다르다.** 그쪽은 사용자가 적은 값이라 전용인지 공급인지
     * 모르고 표시용이다. 이쪽은 소스가 확정한 값만 담고 매칭에 쓴다. 자세한 이유는
     * `Asset.exclusiveAreaM2` KDoc 참고.
     */
    @Column(name = "exclusive_area_m2", precision = 10, scale = 4)
    val exclusiveAreaM2: BigDecimal? = null,
) {
    // 위치 인자로 넘기지 않는다 — areaPyeong과 exclusiveAreaM2가 둘 다 BigDecimal?이라
    // 순서가 바뀌어도 컴파일된다. 그러면 표시용 값이 매칭 키로 들어가고 조용히 틀린다.
    fun toDomain() = Asset.reconstruct(
        id = id, userId = userId, accountId = accountId, category = category, type = type,
        sourceType = sourceType, name = name, symbol = symbol, quantity = quantity,
        purchasePrice = purchasePrice, currentValue = currentValue, currency = currency,
        valuationMethod = valuationMethod, confidenceLevel = confidenceLevel,
        lastUpdatedAt = lastUpdatedAt, createdAt = createdAt, memo = memo,
        subType = subType, loanAmount = loanAmount, maturityDate = maturityDate,
        liquidityType = liquidityType, areaPyeong = areaPyeong, priceAsOf = priceAsOf,
        exclusiveAreaM2 = exclusiveAreaM2,
    )

    companion object {
        fun fromDomain(a: Asset) = AssetEntity(
            id = a.id, userId = a.userId, accountId = a.accountId, category = a.category,
            type = a.type, sourceType = a.sourceType, name = a.name, symbol = a.symbol,
            quantity = a.quantity, purchasePrice = a.purchasePrice,
            currentValue = a.currentValue, currency = a.currency,
            valuationMethod = a.valuationMethod, confidenceLevel = a.confidenceLevel,
            lastUpdatedAt = a.lastUpdatedAt, createdAt = a.createdAt, memo = a.memo,
            subType = a.subType, loanAmount = a.loanAmount, maturityDate = a.maturityDate,
            liquidityType = a.liquidityType, areaPyeong = a.areaPyeong,
            priceAsOf = a.priceAsOf, exclusiveAreaM2 = a.exclusiveAreaM2,
        )
    }
}
