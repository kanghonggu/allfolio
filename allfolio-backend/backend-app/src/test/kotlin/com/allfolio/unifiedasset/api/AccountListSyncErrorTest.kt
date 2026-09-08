package com.allfolio.unifiedasset.api

import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.application.usecase.AuthorizationService
import com.allfolio.unifiedasset.application.usecase.AutoSyncTrigger
import com.allfolio.unifiedasset.application.usecase.CreateAccountUseCase
import com.allfolio.unifiedasset.application.usecase.DeleteAccountUseCase
import com.allfolio.unifiedasset.application.usecase.DeleteAssetUseCase
import com.allfolio.unifiedasset.application.usecase.GetSyncStatusUseCase
import com.allfolio.unifiedasset.application.usecase.ImportCsvUseCase
import com.allfolio.unifiedasset.application.usecase.PerformanceSnapshotService
import com.allfolio.unifiedasset.application.usecase.SyncAccountUseCase
import com.allfolio.unifiedasset.application.usecase.TestConnectionUseCase
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * **계좌 목록이 마지막 동기화 실패 사유를 함께 내려준다** (AF-194).
 *
 * 사유는 이미 `ua_sync_logs.error_message`에 저장되고 있었지만 내려주는 곳이 `/sync-status`
 * 뿐이었다. 목록 화면은 그걸 안 불러서 **계좌가 ERROR라는 것까지만 보이고 왜인지는 안 보였다.**
 *
 * 스키마는 안 바꿨다 — `ua_accounts.last_sync_error` 같은 컬럼을 만들면 비정규화 사본이 늘고
 * 마이그레이션이 필요하다. 읽기 경로로 해결된다.
 */
class AccountListSyncErrorTest {

    private val accountRepository = mock(AccountRepository::class.java)
    private val syncLogRepository = mock(SyncLogRepository::class.java)

    private val controller = AccountController(
        mock(CreateAccountUseCase::class.java),
        mock(DeleteAccountUseCase::class.java),
        mock(DeleteAssetUseCase::class.java),
        mock(SyncAccountUseCase::class.java),
        mock(ImportCsvUseCase::class.java),
        mock(TestConnectionUseCase::class.java),
        accountRepository,
        mock(AssetRepository::class.java),
        mock(StockTradeRepository::class.java),
        syncLogRepository,
        mock(GetSyncStatusUseCase::class.java),
        mock(AutoSyncTrigger::class.java),
        mock(PerformanceSnapshotService::class.java),
        AuthorizationService(accountRepository),
    )

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(controller)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private fun account(userId: UUID, name: String) = Account.create(
        userId = userId, provider = AccountProvider.BINANCE,
        accountType = AccountType.EXCHANGE, accountName = name,
    )

    private fun log(accountId: UUID, userId: UUID, status: SyncLogStatus, message: String?) =
        SyncLog.create(accountId, userId, SyncTrigger.SCHEDULED, status, 0, message)

    @Test
    fun `마지막 동기화가 실패면 사유를 함께 내려준다`() {
        val userId = UUID.randomUUID()
        val acct = account(userId, "실패한 계좌")
        `when`(accountRepository.findByUserId(userId)).thenReturn(listOf(acct))
        `when`(syncLogRepository.findLatestByUserId(userId)).thenReturn(
            mapOf(acct.id to log(acct.id, userId, SyncLogStatus.ERROR, "API 키가 만료되었습니다")),
        )

        mockMvc.get("/api/unified/accounts") { header("X-User-Id", userId.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].lastSyncError") { value("API 키가 만료되었습니다") }
            }
    }

    /**
     * 🔴 **복구된 계좌가 옛 실패를 계속 달고 있으면 안 된다.** 로그에는 지난 실패가 남아
     * 있지만 가장 최근 것이 성공이면 사유는 없다.
     */
    @Test
    fun `마지막 동기화가 성공이면 사유는 null이다`() {
        val userId = UUID.randomUUID()
        val acct = account(userId, "복구된 계좌")
        `when`(accountRepository.findByUserId(userId)).thenReturn(listOf(acct))
        `when`(syncLogRepository.findLatestByUserId(userId)).thenReturn(
            // 성공 로그에 errorMessage가 남아 있어도 무시해야 한다 —
            // status를 안 보고 errorMessage만 실으면 이 테스트가 깨진다.
            mapOf(acct.id to log(acct.id, userId, SyncLogStatus.SUCCESS, "예전 실패 사유")),
        )

        mockMvc.get("/api/unified/accounts") { header("X-User-Id", userId.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].lastSyncError") { doesNotExist() }
            }
    }

    @Test
    fun `동기화 이력이 없으면 사유는 null이다`() {
        val userId = UUID.randomUUID()
        val acct = account(userId, "새 계좌")
        `when`(accountRepository.findByUserId(userId)).thenReturn(listOf(acct))
        `when`(syncLogRepository.findLatestByUserId(userId)).thenReturn(emptyMap())

        mockMvc.get("/api/unified/accounts") { header("X-User-Id", userId.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].lastSyncError") { doesNotExist() }
            }
    }

    /**
     * 🔴 **계좌 수와 무관하게 로그 조회는 한 번이다.** 계좌마다 읽으면 N+1이고,
     * 계좌가 늘수록 목록 화면이 느려진다.
     */
    @Test
    fun `계좌가 셋이어도 로그는 한 번만 읽는다`() {
        val userId = UUID.randomUUID()
        val accounts = listOf("a", "b", "c").map { account(userId, it) }
        `when`(accountRepository.findByUserId(userId)).thenReturn(accounts)
        `when`(syncLogRepository.findLatestByUserId(userId)).thenReturn(
            mapOf(accounts[1].id to log(accounts[1].id, userId, SyncLogStatus.ERROR, "두 번째만 실패")),
        )

        mockMvc.get("/api/unified/accounts") { header("X-User-Id", userId.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].lastSyncError") { doesNotExist() }
                jsonPath("$[1].lastSyncError") { value("두 번째만 실패") }
                jsonPath("$[2].lastSyncError") { doesNotExist() }
            }

        verify(syncLogRepository).findLatestByUserId(userId)
    }
}
