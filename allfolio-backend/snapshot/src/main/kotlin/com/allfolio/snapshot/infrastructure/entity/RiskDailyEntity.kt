package com.allfolio.snapshot.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * risk_daily — INSERT ONLY
 *
 * 설계 원칙:
 * - UPDATE 금지 (@Immutable)
 * - 복합 PK: (tenantId, portfolioId, date)
 */
@Entity
@Immutable
@Table(name = "risk_daily")
class RiskDailyEntity(

    @EmbeddedId
    val id: SnapshotDailyId,

    @Column(nullable = false, precision = 30, scale = 10)
    val volatility: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val annualizedVolatility: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val var95: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val maxDrawdown: BigDecimal,

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RiskDailyEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "RiskDailyEntity(id=$id, annualVol=$annualizedVolatility, var95=$var95, mdd=$maxDrawdown)"
}
