package com.allfolio.api.snapshot

import com.allfolio.auth.PortfolioAuthorizationService
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.portfolio.domain.Portfolio
import com.allfolio.snapshot.application.GenerateDailySnapshotUseCase
import com.allfolio.snapshot.application.GenerateSnapshotCommand
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.RiskDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import com.allfolio.test.FakePortfolioRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SnapshotControllerAuthorizationTest {
    private val generateDailySnapshotUseCase = mock(GenerateDailySnapshotUseCase::class.java)
    private val snapshotCache = mock(SnapshotCacheRepository::class.java)

    @Test
    fun `owned portfolio snapshot generate fixes tenant id to user id`() {
        val userId = UUID.randomUUID()
        val attackerTenantId = UUID.randomUUID()
        val portfolio = portfolio(userId)
        val date = LocalDate.of(2026, 6, 30)
        val expectedCommand = GenerateSnapshotCommand(
            tenantId = userId,
            portfolioId = portfolio.id.value,
            date = date,
            marketPrices = emptyMap(),
            yesterdayNav = BigDecimal.ZERO,
            externalCashFlow = BigDecimal.ZERO,
            recentDailyReturns = emptyList(),
        )
        doReturn(snapshotPair(userId, portfolio.id.value, date))
            .`when`(generateDailySnapshotUseCase)
            .generate(expectedCommand)
        val mockMvc = mockMvc(portfolio)

        mockMvc.post("/api/snapshots/daily") {
            header("X-User-Id", userId)
            contentType = MediaType.APPLICATION_JSON
            content = snapshotBody(attackerTenantId, portfolio.id.value, date)
        }.andExpect {
            status { isOk() }
        }

        verify(generateDailySnapshotUseCase).generate(expectedCommand)
    }

    @Test
    fun `another user's portfolio snapshot generate returns 404`() {
        val portfolio = portfolio(UUID.randomUUID())
        val mockMvc = mockMvc(portfolio)

        mockMvc.post("/api/snapshots/daily") {
            header("X-User-Id", UUID.randomUUID())
            contentType = MediaType.APPLICATION_JSON
            content = snapshotBody(UUID.randomUUID(), portfolio.id.value, LocalDate.of(2026, 6, 30))
        }.andExpect {
            status { isNotFound() }
        }

        verifyNoInteractions(generateDailySnapshotUseCase, snapshotCache)
    }

    private fun mockMvc(portfolio: Portfolio) = MockMvcBuilders
        .standaloneSetup(
            SnapshotController(
                generateDailySnapshotUseCase,
                snapshotCache,
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

    private fun snapshotBody(tenantId: UUID, portfolioId: UUID, date: LocalDate): String =
        """
        {
          "tenantId": "$tenantId",
          "portfolioId": "$portfolioId",
          "date": "$date",
          "marketPrices": {},
          "yesterdayNav": 0,
          "externalCashFlow": 0,
          "recentDailyReturns": []
        }
        """.trimIndent()

    private fun snapshotPair(
        tenantId: UUID,
        portfolioId: UUID,
        date: LocalDate,
    ): Pair<PerformanceDailyEntity, RiskDailyEntity> = Pair(
        PerformanceDailyEntity(
            id = SnapshotDailyId(tenantId, portfolioId, date),
            nav = BigDecimal.TEN,
            dailyReturn = BigDecimal.ZERO,
            cumulativeReturn = BigDecimal.ZERO,
            benchmarkReturn = null,
            alpha = null,
        ),
        RiskDailyEntity(
            id = SnapshotDailyId(tenantId, portfolioId, date),
            volatility = BigDecimal.ZERO,
            annualizedVolatility = BigDecimal.ZERO,
            var95 = BigDecimal.ZERO,
            maxDrawdown = BigDecimal.ZERO,
        )
    )
}
