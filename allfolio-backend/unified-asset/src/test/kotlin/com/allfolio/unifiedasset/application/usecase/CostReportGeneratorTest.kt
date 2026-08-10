package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.CostLedgerSource
import com.allfolio.unifiedasset.application.port.CostRecord
import com.allfolio.unifiedasset.application.port.UserBenchmarkLookup
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.report.domain.archive.ReportPeriod
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class CostReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeCostLedger(private val all: List<CostRecord>) : CostLedgerSource {
        override fun findCosts(userId: UUID, from: LocalDate, to: LocalDate) =
            all.filter { it.tradeDate in from..to }.sortedBy { it.tradeDate }
    }
    private class FakeNavSource(private val points: List<NavPoint>) : NavHistorySource {
        override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate) =
            points.filter { it.date in from..to }
    }
    private class FakeCashFlowRepo : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) = emptyList<CashFlow>()
        override fun findByUserId(userId: UUID) = emptyList<CashFlow>()
        override fun delete(id: UUID) {}
        override fun deleteByAccountId(accountId: UUID) {}
    }
    private class FakeUserBm : UserBenchmarkLookup {
        override fun get(userId: UUID): BenchmarkType? = null
    }
    private class FakeBmStore : BenchmarkDailyStore {
        override fun latestDate(type: BenchmarkType): LocalDate? = null
        override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) {}
        override fun series(type: BenchmarkType, from: LocalDate, to: LocalDate) = emptyList<Pair<LocalDate, BigDecimal>>()
    }

    private fun cost(day: Int, provider: String, fee: String, tax: String, type: String = "BUY", name: String = "삼성전자") =
        CostRecord(LocalDate.of(2026, 6, day), name, "005930", "$provider 계좌", provider, type, BigDecimal(fee), BigDecimal(tax))

    private fun nav(day: Int, v: String) = NavPoint(LocalDate.of(2026, 6, day), BigDecimal(v))

    private fun generator(costs: List<CostRecord>, navs: List<NavPoint> = emptyList()): CostReportGenerator {
        val analysis = GetReturnsAnalysisUseCase(FakeNavSource(navs), FakeCashFlowRepo(), FakeUserBm(), FakeBmStore())
        return CostReportGenerator(FakeCostLedger(costs), analysis)
    }

    @Test
    fun `summary totals fee and tax`() {
        val body = mapper.readTree(generator(listOf(
            cost(3, "KIS", "1000", "230"),
            cost(20, "BINANCE", "500", "0"),
        )).generate(userId, period).bodyJson)
        val s = body["summary"]
        assertEquals(1730.0, s["totalCost"].asDouble(), 0.01)
        assertEquals(1500.0, s["brokerFee"].asDouble(), 0.01)
        assertEquals(230.0, s["tradingTax"].asDouble(), 0.01)
        assertEquals(2, s["tradeCount"].asInt())
    }

    @Test
    fun `byType weights sum to about 100`() {
        val body = mapper.readTree(generator(listOf(cost(3, "KIS", "1000", "1000"))).generate(userId, period).bodyJson)
        val sum = body["byType"].sumOf { it["weight"].asDouble() }
        assertEquals(100.0, sum, 0.1)
    }

    @Test
    fun `byBroker aggregates fee and tax per provider`() {
        val body = mapper.readTree(generator(listOf(
            cost(3, "KIS", "1000", "230"),
            cost(4, "KIS", "500", "100"),
            cost(20, "BINANCE", "300", "0"),
        )).generate(userId, period).bodyJson)
        val kis = body["byBroker"].first { it["broker"].asText() == "KIS" }
        assertEquals(1500.0, kis["fee"].asDouble(), 0.01)
        assertEquals(330.0, kis["tax"].asDouble(), 0.01)
        assertEquals(1830.0, kis["total"].asDouble(), 0.01)
    }

    @Test
    fun `monthly aggregates by year-month`() {
        val body = mapper.readTree(generator(listOf(
            cost(3, "KIS", "1000", "0"),
            cost(20, "KIS", "500", "230"),
        )).generate(userId, period).bodyJson)
        assertEquals(1, body["monthly"].size())
        assertEquals("2026-06", body["monthly"][0]["month"].asText())
        assertEquals(1730.0, body["monthly"][0]["total"].asDouble(), 0.01)
    }

    @Test
    fun `cost ratio computed from average nav`() {
        val body = mapper.readTree(
            generator(listOf(cost(3, "KIS", "1000", "0")), navs = listOf(nav(1, "10000000"), nav(30, "10000000")))
                .generate(userId, period).bodyJson
        )
        assertEquals(0.01, body["summary"]["costRatio"].asDouble(), 0.001)
        assertEquals(0.12, body["summary"]["annualizedTer"].asDouble(), 0.01)
    }

    @Test
    fun `cost ratio null when nav insufficient`() {
        val body = mapper.readTree(
            generator(listOf(cost(3, "KIS", "1000", "0")), navs = listOf(nav(1, "10000000")))
                .generate(userId, period).bodyJson
        )
        assertTrue(body["summary"]["costRatio"].isNull)
        assertTrue(body["summary"]["annualizedTer"].isNull)
        assertTrue(body["summary"]["costVsProfit"].isNull)
    }

    @Test
    fun `cost vs profit null when pnl is zero`() {
        val body = mapper.readTree(
            generator(listOf(cost(3, "KIS", "1000", "0")), navs = listOf(nav(1, "10000000"), nav(30, "10000000")))
                .generate(userId, period).bodyJson
        )
        assertTrue(body["summary"]["costVsProfit"].isNull)
        assertTrue(body["summary"]["costRatio"].isNumber)
        assertEquals(0.0, body["summary"]["investmentPnl"].asDouble(), 0.01)
    }

    @Test
    fun `body에 사실형 insights가 포함된다`() {
        val body = mapper.readTree(
            generator(listOf(cost(3, "KIS", "1000", "230"))).generate(userId, period).bodyJson
        )
        val insights = body["insights"]
        assertTrue(insights != null && !insights.isNull)
        val labels = insights.map { it["label"].asText() }
        assertTrue(labels.contains("최대 비용 처"))
        assertTrue(labels.contains("비용 구성"))
    }

    @Test
    fun `zero trades yields valid empty report without exception`() {
        val generated = generator(emptyList()).generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["summary"]["totalCost"].asDouble(), 0.01)
        assertEquals(0, body["details"].size())
        assertTrue(body["byType"].isEmpty)
    }
}
