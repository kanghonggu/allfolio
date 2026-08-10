package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * AF-90: 쓰기 작업 직후 자동 동기화.
 * 프론트가 저장 직후부터 "반영 중"을 표시할 수 있도록 요청 스레드에서 SYNCING을 먼저 찍고,
 * 비동기 제출이 실패하면 계좌가 SYNCING에 갇히지 않아야 한다.
 */
class AutoSyncTriggerTest {

    private val accountId: UUID = UUID.randomUUID()

    @Test
    fun `요청 스레드에서 계좌를 SYNCING으로 표시한 뒤 비동기 실행을 제출한다`() {
        val repository = RecordingAccountRepository()
        val dispatcher = RecordingDispatcher()

        AutoSyncTrigger(repository, dispatcher).requestSync(accountId)

        assertThat(repository.statuses).containsExactly(AccountStatus.SYNCING)
        assertThat(dispatcher.dispatched).containsExactly(accountId)
    }

    @Test
    fun `비동기 제출이 거부되면 SYNCING에 갇히지 않도록 ACTIVE로 되돌린다`() {
        val repository = RecordingAccountRepository()

        AutoSyncTrigger(repository, RecordingDispatcher(reject = true)).requestSync(accountId)

        assertThat(repository.statuses).containsExactly(AccountStatus.SYNCING, AccountStatus.ACTIVE)
    }

    @Test
    fun `상태 표시가 실패해도 동기화 제출은 계속한다`() {
        val dispatcher = RecordingDispatcher()

        AutoSyncTrigger(RecordingAccountRepository(failStatusUpdate = true), dispatcher)
            .requestSync(accountId)

        assertThat(dispatcher.dispatched).containsExactly(accountId)
    }

    @Test
    fun `실행 빈은 AUTO 트리거로 동기화를 돌린다`() {
        val repository = RecordingAccountRepository()
        val runner = RecordingRunner()

        AsyncAccountSyncExecutor(runner, repository).dispatch(accountId)

        assertThat(runner.calls).containsExactly(accountId to SyncTrigger.AUTO)
        // 정상 경로에서 상태 전이는 SyncAccountUseCase 책임 — 여기서 덧칠하지 않는다
        assertThat(repository.statuses).isEmpty()
    }

    @Test
    fun `동기화가 예외를 던지면 계좌 상태를 ERROR로 확정한다`() {
        val repository = RecordingAccountRepository()

        AsyncAccountSyncExecutor(RecordingRunner(throwOnExecute = true), repository).dispatch(accountId)

        assertThat(repository.statuses).containsExactly(AccountStatus.ERROR)
    }

    // ── fakes ────────────────────────────────────────────────────

    private class RecordingDispatcher(
        private val reject: Boolean = false,
    ) : AccountSyncDispatcher {
        val dispatched = mutableListOf<UUID>()
        override fun dispatch(accountId: UUID) {
            if (reject) throw IllegalStateException("executor rejected")
            dispatched += accountId
        }
    }

    private class RecordingRunner(
        private val throwOnExecute: Boolean = false,
    ) : AccountSyncRunner {
        val calls = mutableListOf<Pair<UUID, SyncTrigger>>()
        override fun execute(accountId: UUID, trigger: SyncTrigger): SyncResult {
            if (throwOnExecute) throw IllegalStateException("sync blew up")
            calls += accountId to trigger
            return SyncResult(accountId, 1, AccountStatus.ACTIVE)
        }
    }

    private class RecordingAccountRepository(
        private val failStatusUpdate: Boolean = false,
    ) : AccountRepository {
        val statuses = mutableListOf<AccountStatus>()

        override fun updateStatus(id: UUID, status: AccountStatus) {
            if (failStatusUpdate) throw IllegalStateException("db down")
            statuses += status
        }

        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID): List<Account> = emptyList()
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
    }
}
