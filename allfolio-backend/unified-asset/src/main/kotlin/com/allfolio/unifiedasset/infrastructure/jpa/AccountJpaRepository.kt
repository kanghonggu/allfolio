package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.AccountEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface AccountJpaRepository : JpaRepository<AccountEntity, UUID> {
    fun findByUserId(userId: UUID): List<AccountEntity>

    fun findByProviderIn(providers: Collection<com.allfolio.unifiedasset.domain.account.AccountProvider>): List<AccountEntity>

    /**
     * 소유자만 뽑는 스칼라 프로젝션 (AF-193).
     *
     * 엔티티를 만들지 않으므로 `api_key`·`api_secret`의 [com.allfolio.common.crypto.EncryptedStringConverter]가
     * 돌지 않는다 — 복호화가 깨진 계좌도 소유자는 읽힌다. `findById`로 대신하면 같은 예외에 다시 막힌다.
     */
    @Query("SELECT a.userId FROM AccountEntity a WHERE a.id = :id")
    fun findUserIdById(id: UUID): UUID?

    @Modifying
    @Query("UPDATE AccountEntity a SET a.status = :status WHERE a.id = :id")
    fun updateStatus(id: UUID, status: com.allfolio.unifiedasset.domain.account.AccountStatus): Int

    @Modifying
    @Query("UPDATE AccountEntity a SET a.status = :status, a.lastSyncedAt = :syncedAt WHERE a.id = :id")
    fun updateStatusAndSyncedAt(id: UUID, status: com.allfolio.unifiedasset.domain.account.AccountStatus, syncedAt: java.time.LocalDateTime): Int
}
