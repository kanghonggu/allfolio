package com.allfolio.reconciliation.infrastructure.jpa

import com.allfolio.reconciliation.infrastructure.entity.ReconKdEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconResultDetailEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconResultSummaryEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconRunEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface ReconRunJpaRepository : JpaRepository<ReconRunEntity, UUID> {
    fun findByUserIdAndRunDateBetweenOrderByStartedAtDesc(
        userId: UUID, from: LocalDate, to: LocalDate, pageable: Pageable,
    ): List<ReconRunEntity>

    fun findByUserIdOrderByStartedAtDesc(userId: UUID, pageable: Pageable): List<ReconRunEntity>
}

interface ReconResultSummaryJpaRepository : JpaRepository<ReconResultSummaryEntity, UUID> {
    fun findByRunId(runId: UUID): List<ReconResultSummaryEntity>
}

interface ReconResultDetailJpaRepository : JpaRepository<ReconResultDetailEntity, UUID> {
    fun findBySummaryIdIn(summaryIds: Collection<UUID>): List<ReconResultDetailEntity>
}

interface ReconKdJpaRepository : JpaRepository<ReconKdEntity, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<ReconKdEntity>
    fun findByUserIdAndUseYnTrue(userId: UUID): List<ReconKdEntity>
}
