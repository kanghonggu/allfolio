package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

class GetReturnsAnalysisUseCaseTest {

    private val userId = UUID.randomUUID()

    private class FakeNavSource(private val points: List<NavPoint>) : NavHistorySource {
        override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate) =
            points.filter { it.date in from..to }
    }

    private class FakeCashFlowRepo(private val flows: List<CashFlow> = emptyList()) : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) {}
    }

    private fun nav(day: Int, v: String) = NavPoint(LocalDate.of(2026, 6, day), BigDecimal(v))

    @Test
    fun `analyzes arbitrary range`() {
        val useCase = GetReturnsAnalysisUseCase(
            FakeNavSource(listOf(nav(1, "1000"), nav(30, "1100"))), FakeCashFlowRepo(),
        )
        val result = useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))

        assertEquals(LocalDate.of(2026, 6, 30), result.asOfDate)
        assertEquals(2, result.navSeries.size)
        assertEquals(
            0,
            BigDecimal("0.1").compareTo(result.summary.twr!!.setScale(1, RoundingMode.HALF_UP)),
        )
    }

    @Test
    fun `insufficient observations throw`() {
        val useCase = GetReturnsAnalysisUseCase(FakeNavSource(listOf(nav(1, "1000"))), FakeCashFlowRepo())
        assertThrows(InsufficientDataException::class.java) {
            useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
        }
    }

    @Test
    fun `from after to is rejected`() {
        val useCase = GetReturnsAnalysisUseCase(FakeNavSource(emptyList()), FakeCashFlowRepo())
        assertThrows(IllegalArgumentException::class.java) {
            useCase.analyze(userId, LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1))
        }
    }
}
