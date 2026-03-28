package com.allfolio.snapshot.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * performance_daily — INSERT ONLY
 *
 * 설계 원칙:
 * - UPDATE 금지 (@Immutable)
 * - 복합 PK: (tenantId, portfolioId, date)
 */
@Entity
@Immutable
@Table(name = "performance_daily")
class PerformanceDailyEntity(

    @EmbeddedId
    val id: SnapshotDailyId,

    @Column(nullable = false, precision = 30, scale = 10)
    val nav: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val dailyReturn: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val cumulativeReturn: BigDecimal,

    @Column(precision = 30, scale = 10)
    val benchmarkReturn: BigDecimal?,

    @Column(precision = 30, scale = 10)
    val alpha: BigDecimal?,

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PerformanceDailyEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "PerformanceDailyEntity(id=$id, nav=$nav, dailyReturn=$dailyReturn)"
}
