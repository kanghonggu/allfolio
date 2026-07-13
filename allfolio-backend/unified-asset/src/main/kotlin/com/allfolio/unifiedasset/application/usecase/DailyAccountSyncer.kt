package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** syncAll 결과 요약. */
data class SyncBatchResult(val synced: Int, val failed: Int, val total: Int)

/**
 * 자동조회 대상 계좌(외부 API/지갑 기반)를 전부 재동기화한다.
 * 계좌별 오류 격리 — 한 계좌 실패가 다른 계좌·배치 전체에 영향 없음.
 * 실패 계좌는 ua_assets 기존 값 유지(현행 유지).
 *
 * MANUAL·CSV·KIWOOM은 라이브 시세가 없어 제외(사용자 입력값 보호).
 */
@Component
class DailyAccountSyncer(
    private val accountRepository: AccountRepository,
    private val syncRunner: AccountSyncRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun syncAll(): SyncBatchResult {
        val accounts = accountRepository.findByProviders(SYNC_ELIGIBLE_PROVIDERS)
        var synced = 0
        var failed = 0
        accounts.forEach { account ->
            runCatching { syncRunner.execute(account.id) }
                .onSuccess { result ->
                    if (result.status == AccountStatus.ACTIVE) {
                        synced++
                    } else {
                        failed++
                        log.warn("[DailyAccountSyncer] sync returned {} accountId={} provider={}: {}",
                            result.status, account.id, account.provider, result.error)
                    }
                }
                .onFailure { e ->
                    failed++
                    log.error("[DailyAccountSyncer] sync threw accountId={} provider={}",
                        account.id, account.provider, e)
                }
        }
        log.info("[DailyAccountSyncer] synced={} failed={} total={}", synced, failed, accounts.size)
        return SyncBatchResult(synced, failed, accounts.size)
    }

    companion object {
        /** 외부 API/지갑으로 자동 시세 갱신이 가능한 provider(프론트 SYNCABLE_PROVIDERS + STOCK). */
        val SYNC_ELIGIBLE_PROVIDERS: Set<AccountProvider> = setOf(
            AccountProvider.KIS,
            AccountProvider.BINANCE,
            AccountProvider.UPBIT,
            AccountProvider.BITHUMB,
            AccountProvider.COINONE,
            AccountProvider.BYBIT,
            AccountProvider.OKX,
            AccountProvider.WALLET,
            AccountProvider.STOCK,
        )
    }
}
