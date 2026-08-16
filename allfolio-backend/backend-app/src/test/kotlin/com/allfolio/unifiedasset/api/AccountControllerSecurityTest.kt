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
import com.allfolio.unifiedasset.application.usecase.GetSyncStatusUseCase
import com.allfolio.unifiedasset.application.usecase.ImportCsvUseCase
import com.allfolio.unifiedasset.application.usecase.PerformanceSnapshotService
import com.allfolio.unifiedasset.application.usecase.SyncAccountUseCase
import com.allfolio.unifiedasset.application.usecase.TestConnectionUseCase
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.util.UUID

class AccountControllerSecurityTest {

    private val createAccountUseCase = mock(CreateAccountUseCase::class.java)
    private val deleteAccountUseCase = mock(DeleteAccountUseCase::class.java)
    private val syncAccountUseCase = mock(SyncAccountUseCase::class.java)
    private val importCsvUseCase = mock(ImportCsvUseCase::class.java)
    private val testConnectionUseCase = mock(TestConnectionUseCase::class.java)
    private val accountRepository = mock(AccountRepository::class.java)
    private val assetRepository = mock(AssetRepository::class.java)
    private val stockTradeRepository = mock(StockTradeRepository::class.java)
    private val syncLogRepository = mock(SyncLogRepository::class.java)
    private val getSyncStatusUseCase = mock(GetSyncStatusUseCase::class.java)
    private val snapshotService = mock(PerformanceSnapshotService::class.java)
    private val autoSyncTrigger = mock(AutoSyncTrigger::class.java)
    private val authorizationService = AuthorizationService(accountRepository)

    private val controller = AccountController(
        createAccountUseCase,
        deleteAccountUseCase,
        syncAccountUseCase,
        importCsvUseCase,
        testConnectionUseCase,
        accountRepository,
        assetRepository,
        stockTradeRepository,
        syncLogRepository,
        getSyncStatusUseCase,
        autoSyncTrigger,
        snapshotService,
        authorizationService,
    )

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(controller)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `내 account assets 조회는 200과 asset 리스트를 반환한다`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        `when`(accountRepository.findById(accountId)).thenReturn(account(userId))
        `when`(assetRepository.findByAccountId(accountId)).thenReturn(listOf(asset(userId, accountId, "AAPL")))

        mockMvc.get("/api/unified/accounts/$accountId/assets") {
            header("X-User-Id", userId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("AAPL") }
            jsonPath("$[0].accountId") { value(accountId.toString()) }
        }

        verify(accountRepository).findById(accountId)
        verify(assetRepository).findByAccountId(accountId)
    }

    @Test
    fun `남의 account assets 조회는 404로 숨긴다`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        `when`(accountRepository.findById(accountId)).thenReturn(account(UUID.randomUUID()))

        mockMvc.get("/api/unified/accounts/$accountId/assets") {
            header("X-User-Id", userId.toString())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("Account not found: $accountId") }
        }

        verify(accountRepository).findById(accountId)
        verifyNoInteractions(assetRepository)
    }

    @Test
    fun `존재하지 않는 account assets 조회는 404를 반환한다`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        `when`(accountRepository.findById(accountId)).thenReturn(null)

        mockMvc.get("/api/unified/accounts/$accountId/assets") {
            header("X-User-Id", userId.toString())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("Account not found: $accountId") }
        }

        verify(accountRepository).findById(accountId)
        verifyNoInteractions(assetRepository)
    }

    @Test
    fun `남의 계좌 삭제는 404로 숨긴다 (P3 소유권 응답코드 통일)`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        `when`(accountRepository.findById(accountId)).thenReturn(account(UUID.randomUUID()))

        mockMvc.delete("/api/unified/accounts/$accountId") {
            header("X-User-Id", userId.toString())
        }.andExpect {
            status { isNotFound() }
        }

        verify(accountRepository).findById(accountId)
        verifyNoInteractions(assetRepository)
        verifyNoInteractions(deleteAccountUseCase)
    }

    @Test
    fun `내 계좌 삭제는 자식 레코드까지 지우는 삭제 use case에 위임한다`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        `when`(accountRepository.findById(accountId)).thenReturn(account(userId))

        mockMvc.delete("/api/unified/accounts/$accountId") {
            header("X-User-Id", userId.toString())
        }.andExpect {
            status { isNoContent() }
        }

        verify(deleteAccountUseCase).execute(accountId)
    }

    @Test
    fun `X-User-Id 헤더가 없으면 400을 반환한다`() {
        val accountId = UUID.randomUUID()

        mockMvc.get("/api/unified/accounts/$accountId/assets")
            .andExpect {
                status { isBadRequest() }
            }

        verifyNoInteractions(accountRepository, assetRepository)
    }

    private fun account(userId: UUID): Account = Account.create(
        userId = userId,
        provider = AccountProvider.MANUAL,
        accountType = AccountType.MANUAL,
        accountName = "manual",
        currency = "KRW",
    )

    private fun asset(userId: UUID, accountId: UUID, name: String): Asset = Asset.create(
        userId = userId,
        accountId = accountId,
        category = AssetCategory.FINANCIAL,
        type = AssetType.STOCK,
        sourceType = AssetSourceType.MANUAL,
        name = name,
        symbol = name,
        quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal.TEN,
        currentValue = BigDecimal.TEN,
        currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
    )
}
