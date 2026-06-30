package com.allfolio.config

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.PortfolioAuthorizationService
import com.allfolio.auth.UserEntity
import com.allfolio.market.PriceSseController
import com.allfolio.market.PriceSseRegistry
import com.allfolio.pnl.PositionCacheService
import com.allfolio.sse.PnlSseController
import com.allfolio.sse.SseEmitterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@WebMvcTest(controllers = [PriceSseController::class, PnlSseController::class])
@ContextConfiguration(classes = [SseSecurityConfigTest.TestApplication::class])
@Import(
    PriceSseController::class,
    PnlSseController::class,
    SecurityConfig::class,
    SseTokenFilter::class,
    JwtUserIdFilter::class,
    JwtTokenService::class,
    GlobalExceptionHandler::class,
)
@TestPropertySource(
    properties = [
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "allfolio.auth.jwt-secret=01234567890123456789012345678901",
        "allfolio.auth.access-token-minutes=60",
    ]
)
class SseSecurityConfigTest {

    @SpringBootConfiguration
    class TestApplication

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtTokenService: JwtTokenService

    @MockBean
    private lateinit var priceSseRegistry: PriceSseRegistry

    @MockBean
    private lateinit var emitterRegistry: SseEmitterRegistry

    @MockBean
    private lateinit var positionCacheService: PositionCacheService

    @MockBean
    private lateinit var portfolioAuthorizationService: PortfolioAuthorizationService

    @Test
    fun `prices sse stays public without token`() {
        `when`(priceSseRegistry.register()).thenReturn(SseEmitter(60_000L))

        mockMvc.get("/api/sse/prices")
            .andExpect {
                status { isOk() }
                request { asyncStarted() }
            }
    }

    @Test
    fun `pnl sse without token returns 401`() {
        val portfolioId = UUID.randomUUID()

        mockMvc.get("/api/sse/pnl/$portfolioId")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `pnl sse token authenticates and injects user id`() {
        val userId = UUID.randomUUID()
        val portfolioId = UUID.randomUUID()
        val token = tokenFor(userId)
        `when`(emitterRegistry.register(portfolioId)).thenReturn(SseEmitter(60_000L))
        `when`(positionCacheService.getPositions(portfolioId)).thenReturn(emptyMap())

        mockMvc.get("/api/sse/pnl/$portfolioId") {
            param("token", token)
        }.andExpect {
            status { isOk() }
            request { asyncStarted() }
        }

        verify(portfolioAuthorizationService).requireOwnedPortfolio(userId, portfolioId)
        verify(emitterRegistry).register(portfolioId)
    }

    @Test
    fun `pnl sse token for another user's portfolio returns 404`() {
        val userId = UUID.randomUUID()
        val portfolioId = UUID.randomUUID()
        val token = tokenFor(userId)
        doThrow(NoSuchElementException("Portfolio not found: $portfolioId"))
            .`when`(portfolioAuthorizationService)
            .requireOwnedPortfolio(userId, portfolioId)

        mockMvc.get("/api/sse/pnl/$portfolioId") {
            param("token", token)
        }.andExpect {
            status { isNotFound() }
        }
    }

    private fun tokenFor(userId: UUID): String =
        jwtTokenService.issue(
            UserEntity(
                id = userId,
                email = "$userId@example.com",
                passwordHash = "hash",
                displayName = "Test User",
            )
        ).first
}
