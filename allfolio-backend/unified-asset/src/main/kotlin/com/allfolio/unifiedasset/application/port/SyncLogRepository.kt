package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.sync.SyncLog
import java.util.UUID

interface SyncLogRepository {
    fun save(log: SyncLog): SyncLog

    /** created_at 내림차순 최대 limit건. */
    fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog>

    /** 사용자의 계좌별 최신 로그 1건. key=accountId. */
    fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog>

    fun deleteByAccountId(accountId: UUID)
}
