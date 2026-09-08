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
import com.allfolio.unifiedasset.domain.account.StockTrade
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 거래 입력 검증의 **HTTP 계약** (AF-172).
 *
 * ## 도메인 테스트가 이미 있는데 왜 또 쓰나
 *
 * [com.allfolio.unifiedasset.domain.account.StockTradeTest]가 불변식(수량·단가 양수, 총액
 * 일치, 미래 날짜 거부)을 이미 문다. **하지만 그건 도메인이 던진다는 것까지다.** 사용자가
 * 실제로 겪는 것은 그 다음이다:
 *
 * - 던진 예외가 **몇 번으로 나가는가** — 500이면 "서버가 고장 났다"로 보인다
 * - 검증에 걸린 요청이 **자동 동기화를 걸지 않는가** — 저장도 안 됐는데 외부 API를
 *   때리면 낭비고, 동기화가 계좌 상태를 건드리면 부작용이다
 *
 * 둘 다 컨트롤러 코드의 **순서**에 달려 있어서 도메인 테스트로는 못 잡는다.
 *
 * ## 🔴 검증 경로가 둘이고 상태 코드가 다르다
 *
 * | 무엇이 걸리나 | 어디서 | 상태 |
 * | --- | --- | --- |
 * | 수량·단가·총액·날짜 | 도메인 `require` → `IllegalArgumentException` | **400** |
 * | 종목명 공백 | Bean Validation `@field:NotBlank` | **422** |
 *
 * 사용자에겐 똑같이 "입력이 잘못됐다"인데 코드가 갈린다. **400만 처리하는 클라이언트는
 * 종목명 오류를 오류로 못 읽는다.** 지금 동작을 그대로 고정해 두되, 통일할지는 별건이다.
 */
class StockTradeValidationApiTest {

    private val accountRepository = mock(AccountRepository::class.java)
    private val stockTradeRepository = mock(StockTradeRepository::class.java)
    private val autoSyncTrigger = mock(AutoSyncTrigger::class.java)

    private val controller = AccountController(
        mock(CreateAccountUseCase::class.java),
        mock(DeleteAccountUseCase::class.java),
        mock(DeleteAssetUseCase::class.java),
        mock(SyncAccountUseCase::class.java),
        mock(ImportCsvUseCase::class.java),
        mock(TestConnectionUseCase::class.java),
        accountRepository,
        mock(AssetRepository::class.java),
        stockTradeRepository,
        mock(SyncLogRepository::class.java),
        mock(GetSyncStatusUseCase::class.java),
        autoSyncTrigger,
        mock(PerformanceSnapshotService::class.java),
        AuthorizationService(accountRepository),
    )

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(controller)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

    private fun stockAccount(userId: UUID) = Account.create(
        userId = userId, provider = AccountProvider.STOCK,
        accountType = AccountType.STOCK, accountName = "증권계좌",
    )

    private fun body(
        tradeType: String = "BUY",
        stockName: String = "삼성전자",
        quantity: String = "10",
        price: String = "1000",
        totalAmount: String = "10000",
        tradedAt: LocalDate = today,
    ) = """
        {"tradeType":"$tradeType","stockName":"$stockName","symbol":"005930",
         "quantity":$quantity,"price":$price,"totalAmount":$totalAmount,
         "tradedAt":"$tradedAt"}
    """.trimIndent()

    private fun postTrade(userId: UUID, accountId: UUID, json: String) =
        mockMvc.post("/api/unified/accounts/$accountId/stock-trades") {
            header("X-User-Id", userId.toString())
            contentType = MediaType.APPLICATION_JSON
            content = json
        }

    /**
     * **대조군.** 이게 없으면 나머지가 전부 400이어도 "엔드포인트가 그냥 망가진 것"과
     * 구별되지 않는다.
     */
    @Test
    fun `정상 입력은 201이고 자동 동기화를 건다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)
        `when`(stockTradeRepository.save(any(StockTrade::class.java) ?: dummyTrade()))
            .thenAnswer { it.arguments[0] as StockTrade }

        postTrade(userId, account.id, body()).andExpect { status { isCreated() } }

        verify(autoSyncTrigger).requestSync(account.id)
    }

    @Test
    fun `수량이 0이면 400이고 동기화를 걸지 않는다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)

        postTrade(userId, account.id, body(quantity = "0", totalAmount = "0"))
            .andExpect { status { isBadRequest() } }

        verify(autoSyncTrigger, never()).requestSync(account.id)
    }

    @Test
    fun `단가가 음수면 400이다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)

        postTrade(userId, account.id, body(price = "-1000", totalAmount = "-10000"))
            .andExpect { status { isBadRequest() } }

        verify(autoSyncTrigger, never()).requestSync(account.id)
    }

    /** 총액이 수량x단가와 안 맞으면 조작으로 본다 — 8월 QA P0 #2가 넣은 규칙 */
    @Test
    fun `총액이 수량x단가와 다르면 400이다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)

        postTrade(userId, account.id, body(totalAmount = "999999"))
            .andExpect { status { isBadRequest() } }

        verify(autoSyncTrigger, never()).requestSync(account.id)
    }

    /** 판정 기준은 KST다 — 호스트 시계가 아니라(`StockTrade.create`) */
    @Test
    fun `미래 날짜는 400이다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)

        postTrade(userId, account.id, body(tradedAt = today.plusDays(1)))
            .andExpect { status { isBadRequest() } }

        verify(autoSyncTrigger, never()).requestSync(account.id)
    }

    /**
     * 🔴 **여기만 422다.** Bean Validation(`@field:NotBlank`)이 컨트롤러 진입 전에 걸러서
     * 도메인 `require`와 다른 핸들러를 탄다. 공백만 있는 이름도 `NotBlank`가 막는다.
     */
    @Test
    fun `종목명이 공백뿐이면 422다 — 도메인 검증과 상태 코드가 다르다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)

        postTrade(userId, account.id, body(stockName = "   "))
            .andExpect { status { isUnprocessableEntity() } }

        verify(autoSyncTrigger, never()).requestSync(account.id)
    }

    /** 증권 계좌가 아니면 거래내역을 붙일 수 없다 */
    @Test
    fun `증권 계좌가 아니면 400이다`() {
        val userId = UUID.randomUUID()
        val exchange = Account.create(
            userId = userId, provider = AccountProvider.BINANCE,
            accountType = AccountType.EXCHANGE, accountName = "거래소",
        )
        `when`(accountRepository.findById(exchange.id)).thenReturn(exchange)

        postTrade(userId, exchange.id, body()).andExpect { status { isBadRequest() } }

        verify(autoSyncTrigger, never()).requestSync(exchange.id)
    }

    /** 파싱 단계에서 깨지는 값도 500이 아니라 400이어야 한다 */
    @Test
    fun `날짜 형식이 깨지면 400이다`() {
        val userId = UUID.randomUUID()
        val account = stockAccount(userId)
        `when`(accountRepository.findById(account.id)).thenReturn(account)

        postTrade(
            userId, account.id,
            """{"tradeType":"BUY","stockName":"삼성전자","quantity":10,"price":1000,
                "totalAmount":10000,"tradedAt":"2026-13-45"}""",
        ).andExpect { status { isBadRequest() } }

        verify(autoSyncTrigger, never()).requestSync(account.id)
    }

    private fun dummyTrade(): StockTrade = StockTrade.create(
        accountId = UUID.randomUUID(), userId = UUID.randomUUID(),
        tradeType = com.allfolio.unifiedasset.domain.account.StockTradeType.BUY,
        stockName = "x", symbol = null,
        quantity = java.math.BigDecimal.ONE, price = java.math.BigDecimal.ONE,
        totalAmount = java.math.BigDecimal.ONE, tradedAt = today, memo = null,
    )
}
