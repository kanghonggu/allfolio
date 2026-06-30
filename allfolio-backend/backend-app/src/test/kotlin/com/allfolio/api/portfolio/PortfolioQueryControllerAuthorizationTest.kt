package com.allfolio.api.portfolio

import com.allfolio.auth.PortfolioAuthorizationService
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.fx.CurrencyConverter
import com.allfolio.pnl.PositionCacheService
import com.allfolio.portfolio.domain.Portfolio
import com.allfolio.test.FakePortfolioRepository
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class PortfolioQueryControllerAuthorizationTest {
    private val tradeRepository = mock(TradeRawJpaRepository::class.java)
    private val positionCacheService = mock(PositionCacheService::class.java)
    private val currencyConverter = mock(CurrencyConverter::class.java)

    @Test
    fun `owned portfolio trades request succeeds`() {
        val userId = UUID.randomUUID()
        val portfolio = portfolio(userId)
        val mockMvc = mockMvc(portfolio)

        mockMvc.get("/api/portfolios/${portfolio.id.value}/trades") {
            header("X-User-Id", userId)
        }.andExpect {
            status { isOk() }
        }

        verify(tradeRepository).findTop200ByPortfolioIdOrderByExecutedAtDesc(portfolio.id.value)
    }

    @Test
    fun `another user's trades request returns 404`() {
        val portfolio = portfolio(UUID.randomUUID())
        val mockMvc = mockMvc(portfolio)

        mockMvc.get("/api/portfolios/${portfolio.id.value}/trades") {
            header("X-User-Id", UUID.randomUUID())
        }.andExpect {
            status { isNotFound() }
        }

        verifyNoInteractions(tradeRepository)
    }

    @Test
    fun `owned portfolio positions request succeeds`() {
        val userId = UUID.randomUUID()
        val portfolio = portfolio(userId)
        val mockMvc = mockMvc(portfolio)

        mockMvc.get("/api/portfolios/${portfolio.id.value}/positions") {
            header("X-User-Id", userId)
        }.andExpect {
            status { isOk() }
        }

        verify(positionCacheService).getPositions(portfolio.id.value)
    }

    @Test
    fun `another user's positions request returns 404`() {
        val portfolio = portfolio(UUID.randomUUID())
        val mockMvc = mockMvc(portfolio)

        mockMvc.get("/api/portfolios/${portfolio.id.value}/positions") {
            header("X-User-Id", UUID.randomUUID())
        }.andExpect {
            status { isNotFound() }
        }

        verifyNoInteractions(positionCacheService)
    }

    private fun mockMvc(portfolio: Portfolio) = MockMvcBuilders
        .standaloneSetup(
            PortfolioQueryController(
                tradeRepository,
                positionCacheService,
                currencyConverter,
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
}
