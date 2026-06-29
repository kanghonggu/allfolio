package com.allfolio.portfolio.infrastructure.jpa

import com.allfolio.portfolio.infrastructure.entity.PortfolioEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface PortfolioJpaRepository : JpaRepository<PortfolioEntity, UUID> {
    fun findByIdAndUserIdAndDeletedAtIsNull(id: UUID, userId: UUID): PortfolioEntity?

    fun findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId: UUID): List<PortfolioEntity>

    @Modifying
    @Query(
        "UPDATE PortfolioEntity p SET p.deletedAt = :deletedAt " +
            "WHERE p.id = :id AND p.userId = :userId AND p.deletedAt IS NULL"
    )
    fun softDelete(
        @Param("id") id: UUID,
        @Param("userId") userId: UUID,
        @Param("deletedAt") deletedAt: LocalDateTime,
    ): Int
}
