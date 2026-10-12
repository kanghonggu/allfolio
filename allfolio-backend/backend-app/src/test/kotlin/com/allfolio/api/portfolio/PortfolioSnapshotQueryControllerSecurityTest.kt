package com.allfolio.api.portfolio

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.fx.CurrencyConverter
import com.allfolio.fx.FxRateService
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class PortfolioSnapshotQueryControllerSecurityTest {

    private val performanceRepository = mock(PerformanceDailyJpaRepository::class.java)
    private val tradeRepository = mock(TradeRawJpaRepository::class.java)
    private val snapshotCache = mock(SnapshotCacheRepository::class.java)

    private val controller = PortfolioSnapshotQueryController(
        performanceRepository,
        tradeRepository,
        CurrencyConverter(mock(FxRateService::class.java)),
        snapshotCache,
    )

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(controller)
        .build()

    @Test
    fun `snapshot 날짜 조회는 query tenantId가 아니라 X-User-Id tenant를 사용한다`() {
        val userId = UUID.randomUUID()
        val attackerTenantId = UUID.randomUUID()
        val portfolioId = UUID.randomUUID()
        val date = LocalDate.of(2026, 6, 29)

        `when`(performanceRepository.findByPortfolioAndDateAndTenant(portfolioId, date, userId))
            .thenReturn(performance(userId, portfolioId, date, "100"))

        mockMvc.get("/api/portfolios/$portfolioId/snapshot/$date") {
            header("X-User-Id", userId.toString())
            param("tenantId", attackerTenantId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.performance.nav") { value(100) }
        }
    }

    @Test
    fun `snapshot latest는 X-User-Id tenant의 최신 performance만 반환한다`() {
        val userId = UUID.randomUUID()
        val attackerTenantId = UUID.randomUUID()
        val portfolioId = UUID.randomUUID()
        val date = LocalDate.of(2026, 6, 29)

        `when`(performanceRepository.findTopByIdTenantIdAndIdPortfolioIdOrderByIdDateDesc(userId, portfolioId))
            .thenReturn(performance(userId, portfolioId, date, "100"))

        mockMvc.get("/api/portfolios/$portfolioId/snapshot/latest") {
            header("X-User-Id", userId.toString())
            param("tenantId", attackerTenantId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.performance.nav") { value(100) }
        }
    }

    @Test
    fun `snapshot latest는 X-User-Id tenant 데이터가 없으면 다른 tenant query가 있어도 404를 반환한다`() {
        val userId = UUID.randomUUID()
        val attackerTenantId = UUID.randomUUID()
        val portfolioId = UUID.randomUUID()

        `when`(performanceRepository.findTopByIdTenantIdAndIdPortfolioIdOrderByIdDateDesc(userId, portfolioId))
            .thenReturn(null)

        mockMvc.get("/api/portfolios/$portfolioId/snapshot/latest") {
            header("X-User-Id", userId.toString())
            param("tenantId", attackerTenantId.toString())
        }.andExpect {
            status { isNotFound() }
        }
    }

    private fun performance(
        tenantId: UUID,
        portfolioId: UUID,
        date: LocalDate,
        nav: String,
    ) = PerformanceDailyEntity(
        id = SnapshotDailyId(tenantId, portfolioId, date),
        nav = BigDecimal(nav),
        dailyReturn = BigDecimal.ZERO,
        cumulativeReturn = BigDecimal.ZERO,
        benchmarkReturn = null,
        alpha = null,
    )
}
