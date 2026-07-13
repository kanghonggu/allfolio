package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DailyAccountSyncerTest {

    private fun acct(provider: AccountProvider) = Account.create(
        userId = UUID.randomUUID(), provider = provider, accountType = AccountType.STOCK,
        accountName = provider.name, currency = "KRW",
    )

    /** findByProviders에 넘어온 필터를 캡처하고, 미리 지정한 계좌 목록을 반환하는 fake. */
    private class FakeAccountRepository(private val accounts: List<Account>) : AccountRepository {
        var requestedProviders: Collection<AccountProvider>? = null
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> {
            requestedProviders = providers
            return accounts
        }
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID): List<Account> = emptyList()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: AccountStatus) {}
    }

    /** 호출된 accountId를 기록하고, 지정된 id에서는 예외를 던지는 fake runner. */
    private class RecordingSyncRunner(private val throwOn: UUID? = null) : AccountSyncRunner {
        val calledIds = mutableListOf<UUID>()
        override fun execute(accountId: UUID): SyncResult {
            calledIds += accountId
            if (accountId == throwOn) throw RuntimeException("boom")
            return SyncResult(accountId, 1, AccountStatus.ACTIVE)
        }
    }

    @Test
    fun `자동조회 대상 provider 집합으로 계좌를 조회한다`() {
        val repo = FakeAccountRepository(emptyList())
        DailyAccountSyncer(repo, RecordingSyncRunner()).syncAll()
        assertEquals(DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS, repo.requestedProviders?.toSet())
        assertTrue(AccountProvider.MANUAL !in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS)
        assertTrue(AccountProvider.CSV !in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS)
        assertTrue(AccountProvider.KIWOOM !in DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS)
    }

    @Test
    fun `조회된 모든 계좌를 sync하고 한 계좌 실패가 나머지를 막지 않는다`() {
        val a1 = acct(AccountProvider.KIS)
        val a2 = acct(AccountProvider.BINANCE)
        val a3 = acct(AccountProvider.STOCK)
        val repo = FakeAccountRepository(listOf(a1, a2, a3))
        val runner = RecordingSyncRunner(throwOn = a2.id)

        val result = DailyAccountSyncer(repo, runner).syncAll()

        assertEquals(listOf(a1.id, a2.id, a3.id), runner.calledIds)
        assertEquals(3, result.total)
        assertEquals(2, result.synced)
        assertEquals(1, result.failed)
    }
}
