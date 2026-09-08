package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.ReconMutex
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import com.allfolio.unifiedasset.infrastructure.entity.SyncLogEntity
import com.allfolio.unifiedasset.infrastructure.jpa.SyncLogJpaRepository
import com.allfolio.unifiedasset.infrastructure.repository.SyncLogRepositoryImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID

/**
 * **되던지는 경로(경로 2)의 동기화 이력이 롤백에도 살아남는가** (AF-196).
 *
 * ## 왜 대역으로는 못 재나
 *
 * [SyncAccountUseCaseLookupFailureLoggingTest]는 in-memory 대역으로 "저장 메서드가
 * 불렸는가"만 잰다. 대역에는 트랜잭션이 없으니 **커밋되었는가**는 재지 못한다. 그래서
 * `SyncAccountUseCase.execute`가 `@Transactional`인 채로 예외를 되던져 방금 쓴 행이
 * 함께 롤백되는데도 그 테스트는 초록이었다 — 격리해서 재면 통과, 운영에선 사라진다.
 *
 * 이 파일은 **진짜 트랜잭션 매니저와 진짜 `ua_sync_logs` 테이블**로 잰다:
 * - 저장은 실물 [SyncLogRepositoryImpl] — `saveIsolated`의 `REQUIRES_NEW`가 실제로 붙는다
 * - `SyncAccountUseCase`도 **스프링 빈으로 등록**해 `@Transactional` 프록시를 그대로 태운다
 * - 확인은 저장 호출 기록이 아니라 **DB 조회**로 한다
 *
 * ## 대조군
 *
 * "행이 있다"만 보면 롤백이 실제로 일어났는지 알 수 없다. 그래서 [되던지는 경로의 이력은
 * 바깥 트랜잭션이 롤백돼도 남는다]는 같은 트랜잭션에 **평범한 `save`로 쓴 대조군 행**을
 * 하나 더 넣고, 그 행이 **사라졌는지**까지 확인한다. 대조군이 살아 있다면 이 테스트는
 * 롤백을 재고 있지 않은 것이다.
 *
 * ## 테스트 자신의 트랜잭션은 끈다
 *
 * `@DataJpaTest`는 테스트 메서드를 트랜잭션으로 감싸고 끝나면 롤백한다. 그대로 두면
 * 유스케이스가 **테스트의 트랜잭션에 참여**해 커밋·롤백이 전부 테스트 종료 시점으로
 * 미뤄져 아무것도 못 잰다. 그래서 `NOT_SUPPORTED`로 끄고 뒷정리는 손으로 한다.
 */
@DataJpaTest
@ContextConfiguration(classes = [SyncAccountUseCaseLookupFailureRollbackTest.TestConfig::class])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SyncAccountUseCaseLookupFailureRollbackTest {

    @Autowired private lateinit var useCase: SyncAccountUseCase

    @Autowired private lateinit var syncLogRepository: SyncLogRepository

    @Autowired private lateinit var accounts: ProgrammableAccountRepository

    @Autowired private lateinit var jpa: SyncLogJpaRepository

    @Autowired private lateinit var txManager: PlatformTransactionManager

    @AfterEach
    fun cleanUp() {
        jpa.deleteAll()
    }

    /**
     * 🔴 이 태스크의 본체. **수정 전 코드에서 실패한다** — `recordLookupFailure`가 평범한
     * `save`를 쓰면 예외가 프록시 밖으로 나갈 때 행이 함께 롤백돼 `errorMessage` 단언에서
     * 빈 리스트가 나온다.
     */
    @Test
    fun `되던지는 경로의 이력은 바깥 트랜잭션이 롤백돼도 남는다`() {
        val accountId = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val controlAccountId = UUID.randomUUID()
        accounts.owner = owner
        accounts.onFindById = { throw IllegalStateException("db pool exhausted") }

        // 유스케이스의 `@Transactional`은 REQUIRED라 이 트랜잭션에 참여한다. 대조군을
        // 같은 트랜잭션에 쓰기 위해 바깥을 여기서 연다 — 롤백을 일으키는 건 여전히
        // 프록시 밖으로 나가는 예외다.
        assertThrows<IllegalStateException> {
            TransactionTemplate(txManager).execute {
                syncLogRepository.save(
                    SyncLog.create(controlAccountId, owner, SyncTrigger.SCHEDULED, SyncLogStatus.ERROR, 0, "대조군"),
                )
                useCase.execute(accountId, SyncTrigger.SCHEDULED)
            }
        }

        assertThat(logsOf(controlAccountId))
            .describedAs("대조군이 남아 있으면 롤백이 안 일어난 것 — 이 테스트는 아무것도 재고 있지 않다")
            .isEmpty()

        val logs = logsOf(accountId)
        assertThat(logs)
            .describedAs("되던지기 전에 남긴 이력이 바깥 롤백과 함께 사라졌다")
            .hasSize(1)
        val log = logs.single()
        assertThat(log.status).isEqualTo(SyncLogStatus.ERROR)
        assertThat(log.userId).isEqualTo(owner)
        assertThat(log.errorMessage).isEqualTo("db pool exhausted")
        assertThat(log.trigger).isEqualTo(SyncTrigger.SCHEDULED)
    }

    /**
     * 바깥 트랜잭션을 테스트가 열지 않는 경우 — 운영의 호출 모양 그대로다
     * (`DailyAccountSyncer`는 트랜잭션 없이 `execute`를 부른다). 롤백을 일으키는 것은
     * 오직 `execute`의 `@Transactional` 프록시다.
     */
    @Test
    fun `유스케이스가 자기 트랜잭션을 열어도 마찬가지다`() {
        val accountId = UUID.randomUUID()
        val owner = UUID.randomUUID()
        accounts.owner = owner
        accounts.onFindById = { throw IllegalStateException("connection reset") }

        assertThrows<IllegalStateException> { useCase.execute(accountId, SyncTrigger.MANUAL) }

        assertThat(logsOf(accountId).map { it.errorMessage })
            .describedAs("되던지기 전에 남긴 이력이 바깥 롤백과 함께 사라졌다")
            .containsExactly("connection reset")
    }

    /**
     * 경로 3(계좌 없음)은 정상 return이라 바깥이 커밋한다 — **분리하지 않은 채로 남겨 둔
     * 게 맞는지** 확인한다. 이력과 (실물에서의) 계좌 상태 갱신이 한 트랜잭션에 묶여 있다.
     */
    @Test
    fun `정상 return하는 경로는 바깥 트랜잭션과 함께 커밋된다`() {
        val accountId = UUID.randomUUID()
        val owner = UUID.randomUUID()
        accounts.owner = owner
        accounts.onFindById = { null }

        val result = TransactionTemplate(txManager).execute { useCase.execute(accountId, SyncTrigger.AUTO) }!!

        assertThat(result.error).isEqualTo("Account not found")
        assertThat(logsOf(accountId).map { it.errorMessage }).containsExactly("Account not found")
    }

    /** 확인은 저장 호출이 아니라 DB 조회로 한다 — 커밋되지 않은 행은 여기 안 보인다. */
    private fun logsOf(accountId: UUID): List<SyncLog> = syncLogRepository.findByAccountId(accountId, 10)

    /** `findById`는 테스트가 정한 대로, `findUserId`는 그와 무관하게 답한다(실물은 복호화를 안 탄다). */
    class ProgrammableAccountRepository : AccountRepository {
        var onFindById: () -> Account? = { null }
        var owner: UUID? = null

        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = onFindById()
        override fun findByUserId(userId: UUID): List<Account> = emptyList()
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
        override fun findUserId(id: UUID): UUID? = owner
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [SyncLogEntity::class])
    @EnableJpaRepositories(basePackageClasses = [SyncLogJpaRepository::class])
    class TestConfig {

        @Bean
        fun syncLogRepository(jpa: SyncLogJpaRepository): SyncLogRepository = SyncLogRepositoryImpl(jpa)

        @Bean
        fun accountRepository() = ProgrammableAccountRepository()

        /**
         * 빈으로 등록해야 `execute`의 `@Transactional`이 프록시로 붙는다 — 직접 `new` 하면
         * 애너테이션이 아무 일도 하지 않아 이 테스트가 재려는 것이 사라진다.
         */
        @Bean
        fun syncAccountUseCase(
            accounts: ProgrammableAccountRepository,
            syncLogRepository: SyncLogRepository,
        ) = SyncAccountUseCase(
            accountRepository = accounts,
            assetRepository = mock(AssetRepository::class.java),
            adapters = emptyList(),
            snapshotService = mock(PerformanceSnapshotService::class.java),
            fx = IdentityFx,
            syncLogRepository = syncLogRepository,
            reconMutex = NoopMutex,
            cashFlowRepository = mock(CashFlowRepository::class.java),
            stockTradeRepository = FakeStockTradeRepository(),
        )
    }

    object NoopMutex : ReconMutex {
        override fun tryAcquire(userId: UUID): String = "token"
        override fun release(userId: UUID, token: String) = Unit
    }

    object IdentityFx : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount
        override fun rateOf(currency: String): BigDecimal = BigDecimal.ONE
    }
}
