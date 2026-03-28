package com.allfolio.snapshot.infrastructure.mapper

import com.allfolio.risk.domain.RiskSnapshot
import com.allfolio.snapshot.domain.DailyPerformanceSnapshot
import com.allfolio.snapshot.domain.PositionSnapshot
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.PositionDailyEntity
import com.allfolio.snapshot.infrastructure.entity.PositionDailyId
import com.allfolio.snapshot.infrastructure.entity.RiskDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import java.time.LocalDate
import java.util.UUID

/**
 * 도메인 → JPA Entity 변환
 *
 * 원칙:
 * - 단방향 변환 (도메인 → Entity)
 * - Entity → 도메인 복원은 각 Repository 조회 후 Application Layer 책임
 */
object SnapshotMapper {

    fun toPositionEntity(
        snapshot: PositionSnapshot,
        tenantId: UUID,
        portfolioId: UUID,
        date: LocalDate,
    ): PositionDailyEntity = PositionDailyEntity(
        id = PositionDailyId(
            tenantId    = tenantId,
            portfolioId = portfolioId,
            assetId     = snapshot.assetId,
            date        = date,
        ),
        quantity      = snapshot.totalQuantity,
        averageCost   = snapshot.averageCost,
        realizedPnl   = snapshot.realizedPnl,
        unrealizedPnl = snapshot.unrealizedPnl,
    )

    fun toPerformanceEntity(
        snapshot: DailyPerformanceSnapshot,
        tenantId: UUID,
        portfolioId: UUID,
        date: LocalDate,
    ): PerformanceDailyEntity = PerformanceDailyEntity(
        id = SnapshotDailyId(
            tenantId    = tenantId,
            portfolioId = portfolioId,
            date        = date,
        ),
        nav              = snapshot.nav,
        dailyReturn      = snapshot.dailyReturn,
        cumulativeReturn = snapshot.cumulativeReturn,
        benchmarkReturn  = snapshot.benchmarkReturn,
        alpha            = snapshot.alpha,
    )

    fun toRiskEntity(
        snapshot: RiskSnapshot,
        tenantId: UUID,
        portfolioId: UUID,
        date: LocalDate,
    ): RiskDailyEntity = RiskDailyEntity(
        id = SnapshotDailyId(
            tenantId    = tenantId,
            portfolioId = portfolioId,
            date        = date,
        ),
        volatility           = snapshot.volatility,
        annualizedVolatility = snapshot.annualizedVolatility,
        var95                = snapshot.var95,
        maxDrawdown          = snapshot.maxDrawdown,
    )
}
