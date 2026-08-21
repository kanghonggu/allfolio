package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.sync.SyncLog
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class SyncLogView(
    val id: UUID,
    val trigger: String,
    val status: String,
    val syncedCount: Int,
    val errorMessage: String?,
    /** 오프셋을 달고 나간다 — 이유는 [AccountSyncStatus.lastSyncedAt] 참고. */
    val createdAt: OffsetDateTime,
)

fun SyncLog.toView() =
    SyncLogView(id, trigger.name, status.name, syncedCount, errorMessage, createdAt.atOffset(ZoneOffset.UTC))

data class AccountSyncStatus(
    val accountId: UUID,
    val accountName: String,
    val provider: String,
    val status: String,
    /**
     * **오프셋을 달고 나간다.** 존 없이 내보내면 브라우저가 읽는 쪽 로컬 시각으로 해석해
     * 저장(UTC)과 9시간 어긋난다. 프런트에 KST를 박는 것은 답이 아니다 — 한국 사용자에게만
     * 맞고 다른 시간대 사용자는 반대 방향으로 틀린다.
     */
    val lastSyncedAt: OffsetDateTime?,
    val syncable: Boolean,
    val lastLog: SyncLogView?,
)

/** 계좌 목록 + 계좌별 최신 동기화 로그 요약 (AF-9 완료 조건). */
@Service
class GetSyncStatusUseCase(
    private val accountRepository: AccountRepository,
    private val syncLogRepository: SyncLogRepository,
) {
    fun execute(userId: UUID): List<AccountSyncStatus> {
        val latest = syncLogRepository.findLatestByUserId(userId)
        return accountRepository.findByUserId(userId).map { account ->
            AccountSyncStatus(
                accountId = account.id,
                accountName = account.accountName,
                provider = account.provider.name,
                status = account.status.name,
                lastSyncedAt = account.lastSyncedAt?.atOffset(ZoneOffset.UTC),   // 저장 벽시계가 UTC다
                syncable = account.provider in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS,
                lastLog = latest[account.id]?.toView(),
            )
        }
    }
}
