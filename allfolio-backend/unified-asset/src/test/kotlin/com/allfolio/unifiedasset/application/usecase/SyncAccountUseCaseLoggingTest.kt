package com.allfolio.unifiedasset.application.usecase

import com.allfolio.common.crypto.LegacyPlaintextDetectedException
import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
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
import org.junit.jupiter.api.assertThrows
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
        val isolated = mutableListOf<SyncLog>()
        override fun save(log: SyncLog): SyncLog {
            if (failOnSave) throw RuntimeException("log db down")
            saved += log; return log
        }
        /** 바깥 트랜잭션 롤백에 휩쓸리면 안 되는 저장인지 구분해 기록한다 (AF-193). */
        override fun saveIsolated(log: SyncLog): SyncLog {
            isolated += log; return save(log)
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

    /**
     * 계좌 본체는 못 읽지만 소유자 프로젝션은 읽히는 저장소 — 운영 구현과 같은 모양이다.
     *
     * `findById`는 `api_key` 복호화를 타서 터지고, `findUserIdById`는 스칼라 프로젝션이라
     * 컨버터를 타지 않는다 (AccountEntityEncryptionJpaTest가 실제 DB로 이 전제를 검증한다).
     */
    private class LookupFailingAccountRepository(
        private val exception: RuntimeException,
        private val ownerId: UUID?,
    ) : AccountRepository {
        val statusUpdates = mutableListOf<AccountStatus>()
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = throw exception
        override fun findUserIdById(id: UUID): UUID? = ownerId
        override fun findByUserId(userId: UUID): List<Account> = emptyList()
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) { statusUpdates += status }
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

    /**
     * 계좌 행이 통째로 사라진 뒤 실행된 경우 — 소유자를 알 방법이 없다.
     *
     * `ua_sync_logs.user_id`는 NOT NULL이고, 없는 사용자를 지어내는 것보다 안 남기는 편이 낫다.
     * AF-193의 세 경로 중 이 하나만 이력을 남기지 못한다 (자세한 사유는 `recordLookupFailure` KDoc).
     */
    @Test
    fun `계좌 행이 없으면 소유자를 몰라 로그를 남기지 못한다`() {
        val logs = InMemorySyncLogRepository()
        val result = useCase(null, logs).execute(UUID.randomUUID())
        assertEquals(AccountStatus.ERROR, result.status)
        assertEquals("Account not found", result.error)
        assertTrue(logs.saved.isEmpty())
    }

    /** AF-193 경로 3 — 소유자만 읽히면 계좌를 못 읽어도 이력이 남는다. */
    @Test
    fun `계좌를 못 읽어도 소유자를 알면 Account not found 로그가 남는다`() {
        val ownerId = UUID.randomUUID()
        val logs = InMemorySyncLogRepository()
        val repo = object : AccountRepository by RecordingAccountRepository(null) {
            override fun findUserIdById(id: UUID): UUID? = ownerId
        }
        val result = useCase(null, logs, accountRepository = repo).execute(UUID.randomUUID())

        assertEquals(AccountStatus.ERROR, result.status)
        val log = logs.saved.single()
        assertEquals(SyncLogStatus.ERROR, log.status)
        assertEquals(ownerId, log.userId)
        assertEquals("Account not found", log.errorMessage)
    }

    /** AF-193 경로 1 — 민감정보 복호화 실패는 상태만 ERROR로 바꾸고 끝나 이력이 비어 있었다. */
    @Test
    fun `민감정보 재연결 필요로 끝나도 ERROR 로그가 남는다`() {
        val ownerId = UUID.randomUUID()
        val logs = InMemorySyncLogRepository()
        val repo = LookupFailingAccountRepository(LegacyPlaintextDetectedException("legacy"), ownerId)

        val result = useCase(null, logs, accountRepository = repo).execute(UUID.randomUUID(), SyncTrigger.SCHEDULED)

        assertEquals(AccountStatus.ERROR, result.status)
        assertEquals(SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE, result.error)
        val log = logs.saved.single()
        assertEquals(SyncLogStatus.ERROR, log.status)
        assertEquals(SyncTrigger.SCHEDULED, log.trigger)
        assertEquals(ownerId, log.userId)
        assertEquals(SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE, log.errorMessage)
        assertEquals(listOf(AccountStatus.ERROR), repo.statusUpdates)
    }

    /** AF-193 경로 2 — 되던지는 예외는 호출자의 서버 로그로만 남았다. 던지기 전에 이력을 남긴다. */
    @Test
    fun `계좌 조회가 그 밖의 예외로 터져도 던지기 전에 ERROR 로그가 남는다`() {
        val ownerId = UUID.randomUUID()
        val logs = InMemorySyncLogRepository()
        val repo = LookupFailingAccountRepository(IllegalStateException("db down"), ownerId)

        assertThrows<IllegalStateException> {
            useCase(null, logs, accountRepository = repo).execute(UUID.randomUUID(), SyncTrigger.AUTO)
        }

        val log = logs.saved.single()
        assertEquals(SyncLogStatus.ERROR, log.status)
        assertEquals(SyncTrigger.AUTO, log.trigger)
        assertEquals(ownerId, log.userId)
        assertEquals("db down", log.errorMessage)
        assertTrue(repo.statusUpdates.isEmpty())
    }

    /**
     * 세 경로는 바깥 트랜잭션과 분리해 저장해야 한다.
     *
     * 경로 2는 예외를 되던져 `@Transactional execute`의 트랜잭션이 롤백된다 — 같은 트랜잭션에
     * 쓴 이력은 커밋되지 않는다. fake 저장소는 트랜잭션이 없어 이 차이를 못 잡으므로,
     * **어느 저장 메서드를 탔는지**로 대신 단언한다.
     */
    @Test
    fun `계좌 조회 실패 이력은 분리된 트랜잭션으로 저장한다`() {
        val logs = InMemorySyncLogRepository()
        val repo = LookupFailingAccountRepository(IllegalStateException("db down"), UUID.randomUUID())
        assertThrows<IllegalStateException> {
            useCase(null, logs, accountRepository = repo).execute(UUID.randomUUID())
        }
        assertEquals(1, logs.isolated.size)

        // 대조군: 계좌를 손에 넣은 뒤의 기록은 같은 트랜잭션에 남는다
        val acct = account()
        val ok = InMemorySyncLogRepository()
        useCase(acct, ok).execute(acct.id)
        assertEquals(1, ok.saved.size)
        assertTrue(ok.isolated.isEmpty())
    }

    /** 이력 저장이 실패해도 원래 예외가 가려지면 안 된다. */
    @Test
    fun `이력 저장이 실패해도 계좌 조회 예외를 그대로 되던진다`() {
        val logs = InMemorySyncLogRepository().apply { failOnSave = true }
        val repo = LookupFailingAccountRepository(IllegalStateException("db down"), UUID.randomUUID())
        val thrown = assertThrows<IllegalStateException> {
            useCase(null, logs, accountRepository = repo).execute(UUID.randomUUID())
        }
        assertEquals("db down", thrown.message)
        assertTrue(logs.saved.isEmpty())
    }

    /** 소유자 조회까지 터지면 이력은 못 남기되 원래 예외는 그대로 나가야 한다. */
    @Test
    fun `소유자 조회가 터져도 원래 예외를 그대로 되던진다`() {
        val logs = InMemorySyncLogRepository()
        val repo = object : AccountRepository by LookupFailingAccountRepository(IllegalStateException("db down"), null) {
            override fun findUserIdById(id: UUID): UUID? = throw IllegalStateException("projection down")
        }
        val thrown = assertThrows<IllegalStateException> {
            useCase(null, logs, accountRepository = repo).execute(UUID.randomUUID())
        }
        assertEquals("db down", thrown.message)
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
