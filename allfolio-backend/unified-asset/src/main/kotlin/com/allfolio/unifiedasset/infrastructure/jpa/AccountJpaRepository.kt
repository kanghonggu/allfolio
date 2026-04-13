package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.AccountEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface AccountJpaRepository : JpaRepository<AccountEntity, UUID> {
    fun findByUserId(userId: UUID): List<AccountEntity>

    @Modifying
    @Query("UPDATE AccountEntity a SET a.status = :status WHERE a.id = :id")
    fun updateStatus(id: UUID, status: com.allfolio.unifiedasset.domain.account.AccountStatus): Int

    @Modifying
    @Query("UPDATE AccountEntity a SET a.status = :status, a.lastSyncedAt = :syncedAt WHERE a.id = :id")
    fun updateStatusAndSyncedAt(id: UUID, status: com.allfolio.unifiedasset.domain.account.AccountStatus, syncedAt: java.time.LocalDateTime): Int
}
