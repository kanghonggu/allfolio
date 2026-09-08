package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.sync.SyncLog
import java.util.UUID

interface SyncLogRepository {
    fun save(log: SyncLog): SyncLog

    /**
     * 호출한 트랜잭션과 분리해 저장한다 (AF-193).
     *
     * 계좌 조회 예외를 그대로 되던지는 경로(`SyncAccountUseCase.execute`)는 바깥
     * 트랜잭션이 롤백되므로, 같은 트랜잭션에 쓴 이력도 함께 사라진다. 그래서 이 경로만
     * 새 트랜잭션으로 쓴다. 트랜잭션이 없는 구현에서는 [save]와 같다.
     */
    fun saveIsolated(log: SyncLog): SyncLog = save(log)

    /** created_at 내림차순 최대 limit건. */
    fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog>

    /** 사용자의 계좌별 최신 로그 1건. key=accountId. */
    fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog>

    fun deleteByAccountId(accountId: UUID)
}
