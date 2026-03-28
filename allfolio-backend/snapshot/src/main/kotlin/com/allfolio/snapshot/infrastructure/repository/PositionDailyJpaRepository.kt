package com.allfolio.snapshot.infrastructure.repository

import com.allfolio.snapshot.infrastructure.entity.PositionDailyEntity
import com.allfolio.snapshot.infrastructure.entity.PositionDailyId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface PositionDailyJpaRepository : JpaRepository<PositionDailyEntity, PositionDailyId> {

    fun findByIdPortfolioIdAndIdDate(portfolioId: UUID, date: LocalDate): List<PositionDailyEntity>

    fun findByIdTenantIdAndIdPortfolioIdAndIdDateBetween(
        tenantId: UUID,
        portfolioId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<PositionDailyEntity>

    /**
     * 재계산 시 특정 날짜 포지션 삭제 후 재INSERT
     * Snapshot Immutable 원칙에 따라 이 메서드만 DELETE 허용
     */
    @Modifying
    @Query(
        "DELETE FROM PositionDailyEntity p " +
        "WHERE p.id.portfolioId = :portfolioId AND p.id.date = :date"
    )
    fun deleteByPortfolioIdAndDate(
        @Param("portfolioId") portfolioId: UUID,
        @Param("date") date: LocalDate,
    )
}
