package com.allfolio.unifiedasset.api

import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.application.usecase.AuthorizationService
import com.allfolio.unifiedasset.application.usecase.AutoSyncTrigger
import com.allfolio.unifiedasset.application.usecase.CreateAccountCommand
import com.allfolio.unifiedasset.application.usecase.CreateAccountUseCase
import com.allfolio.unifiedasset.application.usecase.GetSyncStatusUseCase
import com.allfolio.unifiedasset.application.usecase.ImportCsvUseCase
import com.allfolio.unifiedasset.application.usecase.PerformanceSnapshotService
import com.allfolio.unifiedasset.application.usecase.SyncAccountUseCase
import com.allfolio.unifiedasset.application.usecase.TestConnectionUseCase
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * AF-90: 거래 저장·삭제와 계좌 생성이 자동 동기화를 걸어야 한다.
 * 예전에는 사용자가 sync 화면에서 "재동기화"를 누르기 전까지 대시보드가 ₩0으로 남았다.
 */
class AccountControllerAutoSyncTest {

    private val createAccountUseCase = mock(CreateAccountUseCase::class.java)
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

    private val controller = AccountController(
        createAccountUseCase,
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
        AuthorizationService(accountRepository),
        object : FxConverter {
            override fun toKrw(amount: BigDecimal, currency: String) = amount
        },
    )

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(controller)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `거래를 저장하면 해당 계좌 자동 동기화를 건다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)
        `when`(stockTradeRepository.save(anyArg())).thenAnswer { it.arguments[0] as StockTrade }

        mockMvc.post("/api/unified/accounts/${account.id}/stock-trades") {
            header("X-User-Id", userId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"tradeType":"BUY","stockName":"삼성전자","symbol":"005930",
                 "quantity":100,"price":70000,"totalAmount":7000000,
                 "fee":0,"tax":0,"tradedAt":"2026-08-05"}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
        }

        verify(autoSyncTrigger).requestSync(account.id)
    }

    @Test
    fun `거래를 삭제하면 해당 계좌 자동 동기화를 건다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        val trade = trade(account.id, userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)
        `when`(stockTradeRepository.findById(trade.id)).thenReturn(trade)

        mockMvc.delete("/api/unified/accounts/${account.id}/stock-trades/${trade.id}") {
            header("X-User-Id", userId.toString())
        }.andExpect {
            status { isNoContent() }
        }

        verify(stockTradeRepository).delete(trade.id)
        verify(autoSyncTrigger).requestSync(account.id)
    }

    @Test
    fun `남의 계좌 거래 저장은 404이고 동기화를 걸지 않는다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(UUID.randomUUID())
        `when`(accountRepository.findById(account.id)).thenReturn(account)

        mockMvc.post("/api/unified/accounts/${account.id}/stock-trades") {
            header("X-User-Id", userId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"tradeType":"BUY","stockName":"삼성전자","symbol":"005930",
                 "quantity":100,"price":70000,"totalAmount":7000000,
                 "fee":0,"tax":0,"tradedAt":"2026-08-05"}
            """.trimIndent()
        }.andExpect {
            status { isNotFound() }
        }

        verifyNoInteractions(autoSyncTrigger)
    }

    @Test
    fun `외부 조회가 가능한 계좌를 만들면 등록 직후 자동 동기화를 건다`() {
        val userId = UUID.randomUUID()
        val account = Account.create(
            userId = userId,
            provider = AccountProvider.BINANCE,
            accountType = AccountType.EXCHANGE,
            accountName = "바이낸스",
            currency = "USD",
        )
        `when`(createAccountUseCase.execute(anyArg())).thenReturn(account)

        mockMvc.post("/api/unified/accounts") {
            header("X-User-Id", userId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountName":"바이낸스","provider":"BINANCE","accountType":"EXCHANGE","currency":"USD"}"""
        }.andExpect {
            status { isCreated() }
        }

        verify(autoSyncTrigger).requestSync(account.id)
    }

    @Test
    fun `수동 계좌 생성은 동기화할 외부 소스가 없어 자동 동기화를 걸지 않는다`() {
        val userId = UUID.randomUUID()
        val account = Account.create(
            userId = userId,
            provider = AccountProvider.MANUAL,
            accountType = AccountType.MANUAL,
            accountName = "수동",
            currency = "KRW",
        )
        `when`(createAccountUseCase.execute(anyArg())).thenReturn(account)

        mockMvc.post("/api/unified/accounts") {
            header("X-User-Id", userId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountName":"수동","provider":"MANUAL","accountType":"MANUAL","currency":"KRW"}"""
        }.andExpect {
            status { isCreated() }
        }

        verifyNoInteractions(autoSyncTrigger)
    }

    /** Kotlin non-null 파라미터에 Mockito any()를 쓰기 위한 캐스팅 헬퍼. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    private fun stockAccount(userId: UUID): Account = Account.create(
        userId = userId,
        provider = AccountProvider.STOCK,
        accountType = AccountType.STOCK,
        accountName = "증권계좌",
        currency = "KRW",
    )

    private fun trade(accountId: UUID, userId: UUID): StockTrade = StockTrade.create(
        accountId = accountId,
        userId = userId,
        tradeType = StockTradeType.BUY,
        stockName = "삼성전자",
        symbol = "005930",
        quantity = BigDecimal(100),
        price = BigDecimal(70_000),
        totalAmount = BigDecimal(7_000_000),
        fee = BigDecimal.ZERO,
        tax = BigDecimal.ZERO,
        tradedAt = LocalDate.of(2026, 8, 5),
        memo = null,
    )
}
