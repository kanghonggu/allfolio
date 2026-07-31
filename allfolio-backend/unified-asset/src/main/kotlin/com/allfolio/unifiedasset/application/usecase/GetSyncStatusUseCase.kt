package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.sync.SyncLog
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

data class SyncLogView(
    val id: UUID,
    val trigger: String,
    val status: String,
    val syncedCount: Int,
    val errorMessage: String?,
    val createdAt: LocalDateTime,
)

fun SyncLog.toView() = SyncLogView(id, trigger.name, status.name, syncedCount, errorMessage, createdAt)

data class AccountSyncStatus(
    val accountId: UUID,
    val accountName: String,
    val provider: String,
    val status: String,
    val lastSyncedAt: LocalDateTime?,
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
                lastSyncedAt = account.lastSyncedAt,
                syncable = account.provider in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS,
                lastLog = latest[account.id]?.toView(),
            )
        }
    }
}
