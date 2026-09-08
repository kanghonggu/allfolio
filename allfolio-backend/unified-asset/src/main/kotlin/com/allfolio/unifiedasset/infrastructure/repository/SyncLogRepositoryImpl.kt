package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.infrastructure.entity.SyncLogEntity
import com.allfolio.unifiedasset.infrastructure.jpa.SyncLogJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class SyncLogRepositoryImpl(private val jpa: SyncLogJpaRepository) : SyncLogRepository {
    override fun save(log: SyncLog): SyncLog = jpa.save(SyncLogEntity.fromDomain(log)).toDomain()

    /**
     * 바깥 트랜잭션이 롤백돼도 이력은 남아야 한다 — 사유는 포트 KDoc 참고 (AF-196).
     *
     * `REQUIRES_NEW`라 바깥 트랜잭션을 잠시 멈추고 **커넥션을 하나 더** 쓴다. 이 경로는
     * 동기화 실패 한 건당 한 번뿐이라 풀에 부담이 되지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun saveIsolated(log: SyncLog): SyncLog = save(log)

    override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> =
        jpa.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, limit)).map { it.toDomain() }

    override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> =
        jpa.findLatestPerAccountByUserId(userId).associate { it.accountId to it.toDomain() }

    override fun deleteByAccountId(accountId: UUID) = jpa.deleteByAccountId(accountId)
}
