package com.allfolio.snapshot.infrastructure.repository

import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface PerformanceDailyJpaRepository : JpaRepository<PerformanceDailyEntity, SnapshotDailyId> {

    fun findByIdPortfolioIdAndIdDateBetween(
        portfolioId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<PerformanceDailyEntity>

    fun findTopByIdPortfolioIdOrderByIdDateDesc(portfolioId: UUID): PerformanceDailyEntity?

    /**
     * 특정 날짜 이전의 가장 최근 스냅샷 — Outbox Processor의 yesterdayNav/previousCumulativeReturn 조회용
     */
    fun findTopByIdPortfolioIdAndIdDateBeforeOrderByIdDateDesc(
        portfolioId: UUID,
        date: LocalDate,
    ): PerformanceDailyEntity?

    /**
     * 특정 날짜 단건 조회 — findById 대신 사용 (Hibernate tuple IN 회피, 인덱스 강제)
     */
    @Query(
        "SELECT p FROM PerformanceDailyEntity p " +
        "WHERE p.id.portfolioId = :portfolioId AND p.id.date = :date AND p.id.tenantId = :tenantId"
    )
    fun findByPortfolioAndDateAndTenant(
        @Param("portfolioId") portfolioId: UUID,
        @Param("date") date: LocalDate,
        @Param("tenantId") tenantId: UUID,
    ): PerformanceDailyEntity?

    @Modifying
    @Query(
        "DELETE FROM PerformanceDailyEntity p " +
        "WHERE p.id.portfolioId = :portfolioId AND p.id.date = :date"
    )
    fun deleteByPortfolioIdAndDate(
        @Param("portfolioId") portfolioId: UUID,
        @Param("date") date: LocalDate,
    )
}
