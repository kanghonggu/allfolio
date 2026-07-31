package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.SyncLogEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface SyncLogJpaRepository : JpaRepository<SyncLogEntity, UUID> {
    fun findByAccountIdOrderByCreatedAtDesc(accountId: UUID, pageable: Pageable): List<SyncLogEntity>

    /** 계좌별 최신 1건 (Postgres DISTINCT ON). */
    @Query(
        value = "SELECT DISTINCT ON (account_id) * FROM ua_sync_logs WHERE user_id = :userId ORDER BY account_id, created_at DESC",
        nativeQuery = true,
    )
    fun findLatestPerAccountByUserId(userId: UUID): List<SyncLogEntity>

    @Modifying
    @Transactional
    fun deleteByAccountId(accountId: UUID)
}
