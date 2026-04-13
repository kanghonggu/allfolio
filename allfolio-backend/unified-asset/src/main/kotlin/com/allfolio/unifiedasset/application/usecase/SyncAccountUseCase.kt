package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.asset.Asset
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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val adapterMap: Map<AccountProvider, SyncAdapter> by lazy {
        adapters.associateBy { it.supportedProvider }
    }

    @Transactional
    fun execute(accountId: UUID): SyncResult {
        val account = accountRepository.findById(accountId)
            ?: return SyncResult(accountId, 0, AccountStatus.ERROR, "Account not found")

        val adapter = adapterMap[account.provider]
            ?: return SyncResult(accountId, 0, AccountStatus.ERROR, "No adapter for ${account.provider}")

        accountRepository.updateStatus(accountId, AccountStatus.SYNCING)

        return try {
            val assets: List<Asset> = adapter.sync(account)
            // 기존 자산 삭제 후 새 자산으로 교체 (full refresh)
            assetRepository.deleteByAccountId(accountId)
            assetRepository.saveAll(assets)
            accountRepository.updateStatus(accountId, AccountStatus.ACTIVE)

            // 이 계좌 유저의 전체 NAV를 스냅샷으로 기록
            val allAssets = assetRepository.findByUserId(account.userId)
            val nav = allAssets.sumOf { it.currentValue }
            snapshotService.record(account.userId, nav)

            log.info("Synced ${assets.size} assets for account $accountId (${account.provider})")
            SyncResult(accountId, assets.size, AccountStatus.ACTIVE)
        } catch (e: Exception) {
            log.error("Sync failed for account $accountId: ${e.message}", e)
            accountRepository.updateStatus(accountId, AccountStatus.ERROR)
            SyncResult(accountId, 0, AccountStatus.ERROR, e.message)
        }
    }
}
