package com.allfolio.snapshot.infrastructure.repository

import com.allfolio.snapshot.infrastructure.entity.RiskDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface RiskDailyJpaRepository : JpaRepository<RiskDailyEntity, SnapshotDailyId> {

    fun findByIdPortfolioIdAndIdDateBetween(
        portfolioId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<RiskDailyEntity>

    fun findTopByIdPortfolioIdOrderByIdDateDesc(portfolioId: UUID): RiskDailyEntity?

    @Query(
        "SELECT r FROM RiskDailyEntity r " +
        "WHERE r.id.portfolioId = :portfolioId AND r.id.date = :date AND r.id.tenantId = :tenantId"
    )
    fun findByPortfolioAndDateAndTenant(
        @Param("portfolioId") portfolioId: UUID,
        @Param("date") date: LocalDate,
        @Param("tenantId") tenantId: UUID,
    ): RiskDailyEntity?

    @Modifying
    @Query(
        "DELETE FROM RiskDailyEntity r " +
        "WHERE r.id.portfolioId = :portfolioId AND r.id.date = :date"
    )
    fun deleteByPortfolioIdAndDate(
        @Param("portfolioId") portfolioId: UUID,
        @Param("date") date: LocalDate,
    )
}
