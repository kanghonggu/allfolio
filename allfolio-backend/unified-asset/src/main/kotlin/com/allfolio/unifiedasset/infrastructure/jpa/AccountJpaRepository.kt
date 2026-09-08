package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.AccountEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface AccountJpaRepository : JpaRepository<AccountEntity, UUID> {
    fun findByUserId(userId: UUID): List<AccountEntity>

    /**
     * 소유자 id만 뽑는다 (AF-193). **엔티티를 만들지 않으므로 민감정보 복호화를 안 탄다** —
     * `findById`가 복호화하다 던지는 그 순간에 이 값이 필요하다.
     */
    @Query("SELECT a.userId FROM AccountEntity a WHERE a.id = :id")
    fun findUserIdById(id: UUID): UUID?

    fun findByProviderIn(providers: Collection<com.allfolio.unifiedasset.domain.account.AccountProvider>): List<AccountEntity>

    @Modifying
    @Query("UPDATE AccountEntity a SET a.status = :status WHERE a.id = :id")
    fun updateStatus(id: UUID, status: com.allfolio.unifiedasset.domain.account.AccountStatus): Int

    @Modifying
    @Query("UPDATE AccountEntity a SET a.status = :status, a.lastSyncedAt = :syncedAt WHERE a.id = :id")
    fun updateStatusAndSyncedAt(id: UUID, status: com.allfolio.unifiedasset.domain.account.AccountStatus, syncedAt: java.time.LocalDateTime): Int
}
