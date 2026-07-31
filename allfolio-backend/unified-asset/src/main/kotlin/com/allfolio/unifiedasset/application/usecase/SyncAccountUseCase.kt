package com.allfolio.unifiedasset.application.usecase

import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.common.crypto.requiresSensitiveDataReconnection
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.ReconMutex
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class SyncResult(
    val accountId: UUID,
    val synced: Int,
    val status: AccountStatus,
    val error: String? = null,
)

@Service
class SyncAccountUseCase(
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val adapters: List<SyncAdapter>,
    private val snapshotService: PerformanceSnapshotService,
    private val fx: FxConverter,
    private val syncLogRepository: SyncLogRepository,
    private val reconMutex: ReconMutex,
) : AccountSyncRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val adapterMap: Map<AccountProvider, SyncAdapter> by lazy {
        adapters.associateBy { it.supportedProvider }
    }

    @Transactional
    override fun execute(accountId: UUID, trigger: SyncTrigger): SyncResult {
        val account = try {
            accountRepository.findById(accountId)
        } catch (e: RuntimeException) {
            if (e.requiresSensitiveDataReconnection()) {
                runCatching { accountRepository.updateStatus(accountId, AccountStatus.ERROR) }
                return SyncResult(accountId, 0, AccountStatus.ERROR, SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE)
            }
            throw e
        } ?: return SyncResult(accountId, 0, AccountStatus.ERROR, "Account not found")

        val adapter = adapterMap[account.provider]
            ?: return SyncResult(accountId, 0, AccountStatus.ERROR, "No adapter for ${account.provider}")
                .also { record(account, trigger, it) }

        // 대사↔동기화 상호 배제 (#17) — 대사 진행 중이면 계좌 상태는 건드리지 않고 건너뛴다
        val lockToken = reconMutex.tryAcquire(account.userId)
            ?: return SyncResult(accountId, 0, AccountStatus.ERROR, "대사가 진행 중이라 동기화를 건너뜁니다")
                .also { record(account, trigger, it) }

        accountRepository.updateStatus(accountId, AccountStatus.SYNCING)

        return try {
            val assets: List<Asset> = adapter.sync(account)
            // 기존 자산 삭제 후 새 자산으로 교체 (full refresh)
            assetRepository.deleteByAccountId(accountId)
            assetRepository.saveAll(assets)
            accountRepository.updateStatus(accountId, AccountStatus.ACTIVE)

            // 이 계좌 유저의 전체 NAV를 스냅샷으로 기록 (통화 혼재 → KRW 환산 후 합산)
            val allAssets = assetRepository.findByUserId(account.userId)
            val nav = allAssets.navInKrw(fx)
            snapshotService.record(account.userId, nav)

            log.info("Synced ${assets.size} assets for account $accountId (${account.provider})")
            SyncResult(accountId, assets.size, AccountStatus.ACTIVE)
                .also { record(account, trigger, it) }
        } catch (e: Exception) {
            log.error("Sync failed for account $accountId: ${e.message}", e)
            accountRepository.updateStatus(accountId, AccountStatus.ERROR)
            val error = if (e.requiresSensitiveDataReconnection()) {
                SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
            } else {
                e.message
            }
            SyncResult(accountId, 0, AccountStatus.ERROR, error)
                .also { record(account, trigger, it) }
        } finally {
            reconMutex.release(account.userId, lockToken)
        }
    }

    /** 동기화 결과를 이력으로 남긴다. 이력 저장 실패가 동기화 결과에 영향을 주지 않게 격리. */
    private fun record(account: Account, trigger: SyncTrigger, result: SyncResult) {
        runCatching {
            syncLogRepository.save(
                SyncLog.create(
                    accountId = account.id,
                    userId = account.userId,
                    trigger = trigger,
                    status = if (result.status == AccountStatus.ACTIVE) SyncLogStatus.SUCCESS else SyncLogStatus.ERROR,
                    syncedCount = result.synced,
                    errorMessage = result.error,
                )
            )
        }.onFailure { e -> log.warn("sync log save failed accountId={}", account.id, e) }
    }
}
