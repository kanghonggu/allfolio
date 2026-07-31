package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_sync_logs")
class SyncLogEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    // trigger는 SQL 예약어라 컬럼명 trigger_type
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    val triggerType: SyncTrigger,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: SyncLogStatus,

    @Column(name = "synced_count", nullable = false)
    val syncedCount: Int,

    @Column(name = "error_message", length = 500)
    val errorMessage: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,
) {
    fun toDomain() = SyncLog.reconstruct(id, accountId, userId, triggerType, status, syncedCount, errorMessage, createdAt)

    companion object {
        fun fromDomain(l: SyncLog) = SyncLogEntity(
            l.id, l.accountId, l.userId, l.trigger, l.status, l.syncedCount, l.errorMessage, l.createdAt,
        )
    }
}
