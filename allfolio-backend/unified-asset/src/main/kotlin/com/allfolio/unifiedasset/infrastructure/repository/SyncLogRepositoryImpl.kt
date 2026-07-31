package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.infrastructure.entity.SyncLogEntity
import com.allfolio.unifiedasset.infrastructure.jpa.SyncLogJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SyncLogRepositoryImpl(private val jpa: SyncLogJpaRepository) : SyncLogRepository {
    override fun save(log: SyncLog): SyncLog = jpa.save(SyncLogEntity.fromDomain(log)).toDomain()

    override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> =
        jpa.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, limit)).map { it.toDomain() }

    override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> =
        jpa.findLatestPerAccountByUserId(userId).associate { it.accountId to it.toDomain() }

    override fun deleteByAccountId(accountId: UUID) = jpa.deleteByAccountId(accountId)
}
