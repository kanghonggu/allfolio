package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.ReconMutex
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.util.UUID

class SyncAccountUseCaseLoggingTest {

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount

        override fun rateOf(currency: String): BigDecimal = BigDecimal.ONE
    }

    private class InMemorySyncLogRepository : SyncLogRepository {
        val saved = mutableListOf<SyncLog>()
        var failOnSave = false
        override fun save(log: SyncLog): SyncLog {
            if (failOnSave) throw RuntimeException("log db down")
            saved += log; return log
        }
        override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> =
            saved.filter { it.accountId == accountId }.sortedByDescending { it.createdAt }.take(limit)
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> =
            saved.filter { it.userId == userId }.groupBy { it.accountId }
                .mapValues { (_, v) -> v.maxBy { it.createdAt } }
        override fun deleteByAccountId(accountId: UUID) { saved.removeAll { it.accountId == accountId } }
    }

    private class FixedAccountRepository(private val account: Account?) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = account
        override fun findByUserId(userId: UUID): List<Account> = listOfNotNull(account)
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class EmptyAssetRepository : AssetRepository {
        override fun save(asset: Asset): Asset = asset
        override fun saveAll(assets: List<Asset>): List<Asset> = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByUserId(userId: UUID): List<Asset> = emptyList()
        override fun findByAccountId(accountId: UUID): List<Asset> = emptyList()
        override fun deleteByAccountId(accountId: UUID) = Unit
        override fun delete(id: UUID) = Unit
    }

    private class FixedSyncAdapter(
        override val supportedProvider: AccountProvider,
        private val result: () -> List<Asset>,
    ) : SyncAdapter {
        override fun sync(account: Account): List<Asset> = result()
    }

    private fun account(provider: AccountProvider = AccountProvider.BINANCE) = Account.create(
        userId = UUID.randomUUID(), provider = provider,
        accountType = AccountType.EXCHANGE, accountName = "t",
    )

    private class FakeMutex(private val acquirable: Boolean = true) : ReconMutex {
        var released = false
        override fun tryAcquire(userId: UUID): String? = if (acquirable) "token" else null
        override fun release(userId: UUID, token: String) { released = true }
    }

    private class RecordingAccountRepository(private val account: Account?) : AccountRepository {
        val statusUpdates = mutableListOf<AccountStatus>()
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = account
        override fun findByUserId(userId: UUID): List<Account> = listOfNotNull(account)
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) { statusUpdates += status }
    }

    private fun useCase(
        account: Account?, logs: InMemorySyncLogRepository,
        adapter: SyncAdapter? = account?.let { FixedSyncAdapter(it.provider) { emptyList() } },
        mutex: ReconMutex = FakeMutex(),
        accountRepository: AccountRepository = FixedAccountRepository(account),
    ) = SyncAccountUseCase(
        accountRepository = accountRepository,
        assetRepository = EmptyAssetRepository(),
        adapters = listOfNotNull(adapter),
        snapshotService = mock(PerformanceSnapshotService::class.java),
        fx = fx,
        syncLogRepository = logs,
        reconMutex = mutex,
        cashFlowRepository = org.mockito.Mockito.mock(com.allfolio.unifiedasset.application.port.CashFlowRepository::class.java),
        stockTradeRepository = FakeStockTradeRepository(),
    )

    @Test
    fun `성공 시 SUCCESS 로그가 트리거·건수와 함께 남는다`() {
        val acct = account()
        val logs = InMemorySyncLogRepository()
        useCase(acct, logs).execute(acct.id, SyncTrigger.SCHEDULED)

        val log = logs.saved.single()
        assertEquals(SyncLogStatus.SUCCESS, log.status)
        assertEquals(SyncTrigger.SCHEDULED, log.trigger)
        assertEquals(0, log.syncedCount)
        assertEquals(acct.userId, log.userId)
    }

    @Test
    fun `어댑터 예외 시 ERROR 로그에 실패 사유가 남는다`() {
        val acct = account()
        val logs = InMemorySyncLogRepository()
        val throwing = FixedSyncAdapter(acct.provider) { throw IllegalStateException("api key expired") }
        useCase(acct, logs, throwing).execute(acct.id)

        val log = logs.saved.single()
        assertEquals(SyncLogStatus.ERROR, log.status)
        assertEquals(SyncTrigger.MANUAL, log.trigger)
        assertEquals("api key expired", log.errorMessage)
    }

    @Test
    fun `어댑터 미지원 계좌도 ERROR 로그가 남는다`() {
        val acct = account(AccountProvider.MANUAL)
        val logs = InMemorySyncLogRepository()
        useCase(acct, logs, FixedSyncAdapter(AccountProvider.BINANCE) { emptyList() }).execute(acct.id)

        assertEquals(SyncLogStatus.ERROR, logs.saved.single().status)
    }

    @Test
    fun `계좌가 없으면 로그를 남기지 않는다`() {
        val logs = InMemorySyncLogRepository()
        val result = useCase(null, logs).execute(UUID.randomUUID())
        assertEquals(AccountStatus.ERROR, result.status)
        assertTrue(logs.saved.isEmpty())
    }

    @Test
    fun `대사 진행 중이면 계좌 상태를 건드리지 않고 건너뛰되 로그는 남긴다`() {
        val acct = account()
        val logs = InMemorySyncLogRepository()
        val repo = RecordingAccountRepository(acct)
        val result = useCase(acct, logs, mutex = FakeMutex(acquirable = false), accountRepository = repo)
            .execute(acct.id)

        assertEquals(AccountStatus.ERROR, result.status)
        assertTrue(result.error!!.contains("대사"))
        assertTrue(repo.statusUpdates.isEmpty())
        assertEquals(SyncLogStatus.ERROR, logs.saved.single().status)
    }

    @Test
    fun `동기화 성공·실패 모두 락을 해제한다`() {
        val acct = account()
        val mutex = FakeMutex()
        useCase(acct, InMemorySyncLogRepository(), mutex = mutex).execute(acct.id)
        assertTrue(mutex.released)

        val mutex2 = FakeMutex()
        val throwing = FixedSyncAdapter(acct.provider) { throw IllegalStateException("boom") }
        useCase(acct, InMemorySyncLogRepository(), adapter = throwing, mutex = mutex2).execute(acct.id)
        assertTrue(mutex2.released)
    }

    @Test
    fun `로그 저장 실패가 동기화 결과에 영향을 주지 않는다`() {
        val acct = account()
        val logs = InMemorySyncLogRepository().apply { failOnSave = true }
        val result = useCase(acct, logs).execute(acct.id)
        assertEquals(AccountStatus.ACTIVE, result.status)
    }
}
