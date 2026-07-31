package com.allfolio.unifiedasset.domain.sync

import java.time.LocalDateTime
import java.util.UUID

enum class SyncTrigger { SCHEDULED, MANUAL }
enum class SyncLogStatus { SUCCESS, ERROR }

/** 계좌 동기화 1회 실행 기록. 스케줄·수동 공통. */
class SyncLog private constructor(
    val id: UUID,
    val accountId: UUID,
    val userId: UUID,
    val trigger: SyncTrigger,
    val status: SyncLogStatus,
    val syncedCount: Int,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
) {
    companion object {
        private const val MAX_ERROR_LENGTH = 500

        fun create(
            accountId: UUID, userId: UUID, trigger: SyncTrigger,
            status: SyncLogStatus, syncedCount: Int, errorMessage: String?,
        ) = SyncLog(
            id = UUID.randomUUID(), accountId = accountId, userId = userId,
            trigger = trigger, status = status, syncedCount = syncedCount,
            errorMessage = errorMessage?.take(MAX_ERROR_LENGTH),
            createdAt = LocalDateTime.now(),
        )

        fun reconstruct(
            id: UUID, accountId: UUID, userId: UUID, trigger: SyncTrigger,
            status: SyncLogStatus, syncedCount: Int, errorMessage: String?, createdAt: LocalDateTime,
        ) = SyncLog(id, accountId, userId, trigger, status, syncedCount, errorMessage, createdAt)
    }
}
