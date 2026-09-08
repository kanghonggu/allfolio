package com.allfolio.unifiedasset.application.usecase

import com.allfolio.common.crypto.LegacyPlaintextDetectedException
import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.ReconMutex
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.util.UUID

/**
 * **계좌를 찾기 전에 끝난 동기화도 `ua_sync_logs`에 남는다** (AF-193).
 *
 * ## 이 파일이 막는 것
 *
 * `record()`는 `Account` 객체를 요구한다. 그래서 계좌 조회 단계에서 끝나는 세 경로는
 * 구조적으로 로그를 못 남겼고, **동기화 현황 화면이 "실패"와 "한 번도 동기화되지 않음"을
 * 구별하지 못했다.** 배치가 조용히 실패해도 서버 로그를 직접 보지 않는 한 알 길이 없었다.
 *
 * ## 대역이 실물과 다른 지점
 *
 * `AccountRepository.findUserId`에는 [findById]를 타는 **기본 구현**이 있다. 여기 대역들은
 * 그 기본 구현을 **일부러 쓰지 않고 재정의**한다 — 실물(`AccountRepositoryImpl`)이 복호화를
 * 건너뛰는 쿼리로 재정의하기 때문이다. 그 재정의가 사라지면
 * [AccountRepositoryUserIdOverrideTest]가 잡는다.
 */
class SyncAccountUseCaseLookupFailureLoggingTest {

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount
        override fun rateOf(currency: String): BigDecimal = BigDecimal.ONE
    }

    private class InMemorySyncLogRepository : SyncLogRepository {
        val saved = mutableListOf<SyncLog>()
        override fun save(log: SyncLog): SyncLog { saved += log; return log }
        override fun findByAccountId(accountId: UUID, limit: Int) = saved.take(limit)
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = emptyMap()
        override fun deleteByAccountId(accountId: UUID) = Unit
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

    private class NoopMutex : ReconMutex {
        override fun tryAcquire(userId: UUID): String? = "token"
        override fun release(userId: UUID, token: String) = Unit
    }

    /**
     * `findById`는 던지거나 null을 주고, `findUserId`는 **그와 무관하게** 답한다.
     * 실물에서 복호화를 안 타는 컬럼 조회에 대응한다.
     */
    private class LookupFailingRepository(
        private val onFindById: () -> Account?,
        private val ownerId: UUID?,
    ) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = onFindById()
        override fun findByUserId(userId: UUID): List<Account> = emptyList()
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
        override fun findUserId(id: UUID): UUID? = ownerId
    }

    private fun useCase(repo: AccountRepository, logs: SyncLogRepository) = SyncAccountUseCase(
        accountRepository = repo,
        assetRepository = EmptyAssetRepository(),
        adapters = emptyList(),
        snapshotService = mock(PerformanceSnapshotService::class.java),
        fx = fx,
        syncLogRepository = logs,
        reconMutex = NoopMutex(),
        cashFlowRepository = mock(CashFlowRepository::class.java),
        stockTradeRepository = FakeStockTradeRepository(),
    )

    /** 경로 1 — 민감정보 재연결이 필요해 즉시 return하던 자리 */
    @Test
    fun `복호화 실패도 실패 로그로 남는다`() {
        val accountId = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val logs = InMemorySyncLogRepository()

        val result = useCase(
            LookupFailingRepository({ throw LegacyPlaintextDetectedException("legacy plaintext") }, owner),
            logs,
        ).execute(accountId, SyncTrigger.MANUAL)

        assertThat(result.status).isEqualTo(AccountStatus.ERROR)
        val log = logs.saved.single()
        assertThat(log.status).isEqualTo(SyncLogStatus.ERROR)
        assertThat(log.accountId).isEqualTo(accountId)
        assertThat(log.userId).describedAs("복호화가 실패해도 소유자는 알아낸다").isEqualTo(owner)
        assertThat(log.errorMessage).isEqualTo(SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE)
        assertThat(log.trigger).isEqualTo(SyncTrigger.MANUAL)
    }

    /**
     * 경로 2 — 그 밖의 예외는 뒤로 던져 `DailyAccountSyncer.onFailure`가 받는다.
     * **던지는 것은 그대로 두고** 기록만 더한다. 그쪽은 서버 로그에만 찍혀서 화면에 안 보였다.
     */
    @Test
    fun `그 밖의 예외는 남기고 나서 다시 던진다`() {
        val accountId = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val logs = InMemorySyncLogRepository()

        assertThrows<IllegalStateException> {
            useCase(
                LookupFailingRepository({ throw IllegalStateException("db pool exhausted") }, owner),
                logs,
            ).execute(accountId, SyncTrigger.SCHEDULED)
        }

        val log = logs.saved.single()
        assertThat(log.status).isEqualTo(SyncLogStatus.ERROR)
        assertThat(log.errorMessage).isEqualTo("db pool exhausted")
        assertThat(log.userId).isEqualTo(owner)
    }

    /** 경로 3 — 계좌 행은 없지만 소유자를 알 수 있는 경우(삭제 직전 조회 등) */
    @Test
    fun `계좌를 못 찾아도 소유자를 알면 남긴다`() {
        val accountId = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val logs = InMemorySyncLogRepository()

        val result = useCase(LookupFailingRepository({ null }, owner), logs)
            .execute(accountId, SyncTrigger.AUTO)

        assertThat(result.error).isEqualTo("Account not found")
        assertThat(logs.saved.single().errorMessage).isEqualTo("Account not found")
    }

    /**
     * 🔴 **소유자를 모르면 남기지 않는다.** `ua_sync_logs.user_id`가 NOT NULL이라 지어낼 수
     * 없다. 그래도 **동기화 자체는 원래대로 끝나야 한다** — 기록 실패가 동작을 막으면
     * 안 된다.
     *
     * 없는 계좌의 이력까지 남기려면 컬럼을 nullable로 바꾸는 결정이 먼저다(스키마 변경).
     */
    @Test
    fun `소유자를 모르면 기록을 건너뛰되 동기화 결과는 그대로다`() {
        val accountId = UUID.randomUUID()
        val logs = InMemorySyncLogRepository()

        val result = useCase(LookupFailingRepository({ null }, ownerId = null), logs)
            .execute(accountId, SyncTrigger.MANUAL)

        assertThat(result.error).isEqualTo("Account not found")
        assertThat(result.status).isEqualTo(AccountStatus.ERROR)
        assertThat(logs.saved).describedAs("user_id를 지어내면 안 된다").isEmpty()
    }

    /**
     * 로그 저장이 실패해도 동기화 결과는 바뀌지 않는다 — `record()`와 같은 판단이다.
     * 이력 남기기가 본 기능을 인질로 잡으면 안 된다.
     */
    @Test
    fun `로그 저장이 터져도 동기화 결과는 그대로다`() {
        val accountId = UUID.randomUUID()
        val failing = object : SyncLogRepository {
            override fun save(log: SyncLog): SyncLog = throw RuntimeException("log db down")
            override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> = emptyList()
            override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = emptyMap()
            override fun deleteByAccountId(accountId: UUID) = Unit
        }

        val result = useCase(LookupFailingRepository({ null }, UUID.randomUUID()), failing)
            .execute(accountId, SyncTrigger.MANUAL)

        assertThat(result.error).isEqualTo("Account not found")
    }
}
