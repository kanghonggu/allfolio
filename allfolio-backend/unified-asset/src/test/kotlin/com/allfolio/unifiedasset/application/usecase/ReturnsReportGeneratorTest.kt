package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ReturnsReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeNavSource(private val points: List<NavPoint>) : NavHistorySource {
        override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate) =
            points.filter { it.date in from..to }
    }

    private class FakeCashFlowRepo(private val flows: List<CashFlow>) : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) {}
        override fun deleteByAccountId(accountId: UUID) {}
    }

    private fun nav(day: Int, v: String) = NavPoint(LocalDate.of(2026, 6, day), BigDecimal(v))

    @Test
    fun `generates returns body with period and standard sections`() {
        val generator = ReturnsReportGenerator(
            FakeNavSource(listOf(nav(1, "1000"), nav(15, "1050"), nav(30, "1100"))),
            FakeCashFlowRepo(emptyList()),
        )

        val generated = generator.generate(userId, period)

        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertTrue(body.has("period"))
        assertTrue(body.has("standard"))
        assertTrue(body.has("flowDecomposition"))
        assertTrue(body.has("navSeries"))
        assertEquals(0.1, body["period"]["twr"].asDouble(), 0.001)
        assertTrue(body["standard"].has("SI"))
    }

    @Test
    fun `deposit does not inflate twr in generated body`() {
        val deposit = CashFlow.create(
            userId = userId, accountId = null, flowDate = LocalDate.of(2026, 6, 15),
            type = FlowType.DEPOSIT, amount = BigDecimal("1000"), currency = "KRW",
            amountKrw = BigDecimal("1000"), memo = null,
        )
        val generator = ReturnsReportGenerator(
            FakeNavSource(listOf(nav(1, "1000"), nav(15, "2000"), nav(30, "2200"))),
            FakeCashFlowRepo(listOf(deposit)),
        )

        val body = mapper.readTree(generator.generate(userId, period).bodyJson)

        assertEquals(0.1, body["period"]["twr"].asDouble(), 0.001)
        assertEquals(1000.0, body["flowDecomposition"]["netFlow"].asDouble(), 0.001)
    }

    @Test
    fun `insufficient nav observations throw`() {
        val generator = ReturnsReportGenerator(
            FakeNavSource(listOf(nav(1, "1000"))),
            FakeCashFlowRepo(emptyList()),
        )
        assertThrows(InsufficientDataException::class.java) {
            generator.generate(userId, period)
        }
    }
}
