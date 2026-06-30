package com.allfolio.sse

import com.allfolio.auth.PortfolioAuthorizationService
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.pnl.PositionCacheService
import com.allfolio.portfolio.domain.Portfolio
import com.allfolio.test.FakePortfolioRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

class PnlSseControllerAuthorizationTest {
    private val emitterRegistry = mock(SseEmitterRegistry::class.java)
    private val positionCacheService = mock(PositionCacheService::class.java)
    private val objectMapper = ObjectMapper()

    @Test
    fun `owned portfolio pnl sse subscribe starts stream`() {
        val userId = UUID.randomUUID()
        val portfolio = portfolio(userId)
        val emitter = SseEmitter(1L)
        org.mockito.Mockito.`when`(emitterRegistry.register(portfolio.id.value)).thenReturn(emitter)
        val mockMvc = mockMvc(portfolio)

        mockMvc.get("/api/sse/pnl/${portfolio.id.value}") {
            header("X-User-Id", userId)
        }.andExpect {
            request { asyncStarted() }
        }

        verify(emitterRegistry).register(portfolio.id.value)
    }

    @Test
    fun `another user's portfolio pnl sse subscribe returns 404`() {
        val portfolio = portfolio(UUID.randomUUID())
        val mockMvc = mockMvc(portfolio)

        mockMvc.get("/api/sse/pnl/${portfolio.id.value}") {
            header("X-User-Id", UUID.randomUUID())
        }.andExpect {
            status { isNotFound() }
        }

        verifyNoInteractions(emitterRegistry, positionCacheService)
    }

    private fun mockMvc(portfolio: Portfolio) = MockMvcBuilders
        .standaloneSetup(
            PnlSseController(
                emitterRegistry,
                positionCacheService,
                objectMapper,
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
