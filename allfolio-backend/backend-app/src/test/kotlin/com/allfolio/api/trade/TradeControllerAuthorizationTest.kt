package com.allfolio.api.trade

import com.allfolio.auth.PortfolioAuthorizationService
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.portfolio.domain.Portfolio
import com.allfolio.test.FakePortfolioRepository
import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.application.RecordTradeUseCase
import com.allfolio.trade.domain.TradeId
import com.allfolio.trade.domain.TradeType
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class TradeControllerAuthorizationTest {
    private val recordTradeUseCase = mock(RecordTradeUseCase::class.java)

    @Test
    fun `owned portfolio trade create succeeds`() {
        val userId = UUID.randomUUID()
        val portfolio = portfolio(userId)
        val assetId = UUID.randomUUID()
        val executedAt = LocalDateTime.of(2026, 6, 30, 10, 15, 30)
        val tradeId = TradeId.newId()
        val expectedCommand = RecordTradeCommand(
            tenantId = userId,
            portfolioId = portfolio.id.value,
            assetId = assetId,
            tradeType = TradeType.BUY,
            quantity = BigDecimal.ONE,
            price = BigDecimal("100"),
            fee = BigDecimal.ZERO,
            tradeCurrency = "KRW",
            executedAt = executedAt,
        )
        `when`(recordTradeUseCase.record(expectedCommand)).thenReturn(tradeId)
        val mockMvc = mockMvc(portfolio)

        mockMvc.post("/api/trades") {
            header("X-User-Id", userId)
            contentType = MediaType.APPLICATION_JSON
            content = tradeBody(userId, portfolio.id.value, assetId, executedAt)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.tradeId") { value(tradeId.value.toString()) }
        }

        verify(recordTradeUseCase).record(expectedCommand)
    }

    @Test
    fun `another user's portfolio trade create returns 404`() {
        val ownerId = UUID.randomUUID()
        val requesterId = UUID.randomUUID()
        val portfolio = portfolio(ownerId)
        val mockMvc = mockMvc(portfolio)

        mockMvc.post("/api/trades") {
            header("X-User-Id", requesterId)
            contentType = MediaType.APPLICATION_JSON
            content = tradeBody(
                tenantId = requesterId,
                portfolioId = portfolio.id.value,
                assetId = UUID.randomUUID(),
                executedAt = LocalDateTime.of(2026, 6, 30, 10, 15, 30),
            )
        }.andExpect {
            status { isNotFound() }
        }

        verifyNoInteractions(recordTradeUseCase)
    }

    private fun mockMvc(portfolio: Portfolio) = MockMvcBuilders
        .standaloneSetup(
            TradeController(
                recordTradeUseCase,
                PortfolioAuthorizationService(FakePortfolioRepository(listOf(portfolio))),
            )
        )
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private fun portfolio(userId: UUID): Portfolio = Portfolio.create(
        tenantId = userId,
        name = "Core",
        baseCurrency = "KRW",
        reportingCurrency = "KRW",
    )

    private fun tradeBody(
        tenantId: UUID,
        portfolioId: UUID,
        assetId: UUID,
        executedAt: LocalDateTime,
    ): String =
        """
        {
          "tenantId": "$tenantId",
          "portfolioId": "$portfolioId",
          "assetId": "$assetId",
          "tradeType": "${TradeType.BUY}",
          "quantity": 1,
          "price": 100,
          "fee": 0,
          "tradeCurrency": "KRW",
          "executedAt": "$executedAt"
        }
        """.trimIndent()
}
