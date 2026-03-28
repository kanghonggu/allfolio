package com.allfolio.snapshot.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * position_daily — INSERT ONLY
 *
 * 설계 원칙:
 * - UPDATE 금지 (@Immutable)
 * - 재계산 시 해당 날짜 레코드 DELETE 후 재INSERT
 * - 복합 PK: (tenantId, portfolioId, assetId, date)
 */
@Entity
@Immutable
@Table(name = "position_daily")
class PositionDailyEntity(

    @EmbeddedId
    val id: PositionDailyId,

    @Column(nullable = false, precision = 30, scale = 10)
    val quantity: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val averageCost: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val realizedPnl: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val unrealizedPnl: BigDecimal,

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PositionDailyEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "PositionDailyEntity(id=$id, qty=$quantity, avgCost=$averageCost)"
}
