package com.allfolio.unifiedasset.domain.sync

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** AUTO: 거래 저장·삭제, 계좌 생성 등 쓰기 작업이 자동으로 건 동기화 (AF-90). */
enum class SyncTrigger { SCHEDULED, MANUAL, AUTO }
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
            // 저장 시각은 UTC — 이유는 Account.completeSync 참고
            createdAt = LocalDateTime.now(ZoneOffset.UTC),
        )

        fun reconstruct(
            id: UUID, accountId: UUID, userId: UUID, trigger: SyncTrigger,
            status: SyncLogStatus, syncedCount: Int, errorMessage: String?, createdAt: LocalDateTime,
        ) = SyncLog(id, accountId, userId, trigger, status, syncedCount, errorMessage, createdAt)
    }
}
