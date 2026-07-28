package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.CashflowTradeSource
import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class CashflowReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val acctId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeCashFlowRepo(private val flows: List<CashFlow>) : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) {}
    }
    private class FakeTradeSource(private val trades: List<TradeCashRecord>) : CashflowTradeSource {
        override fun findTrades(userId: UUID, from: LocalDate, to: LocalDate) =
            trades.filter { it.tradeDate in from..to }.sortedBy { it.tradeDate }
    }
    private class FakeAccountRepo(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account) = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID) = accounts
        override fun findByProviders(providers: Collection<AccountProvider>) = emptyList<Account>()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: AccountStatus) {}
    }

    private fun deposit(day: Int, krw: String) = CashFlow.create(
        userId = userId, accountId = acctId, flowDate = LocalDate.of(2026, 6, day),
        type = FlowType.DEPOSIT, amount = BigDecimal(krw), currency = "KRW", amountKrw = BigDecimal(krw), memo = "입금",
    )
    private fun withdrawal(day: Int, krw: String) = CashFlow.create(
        userId = userId, accountId = acctId, flowDate = LocalDate.of(2026, 6, day),
        type = FlowType.WITHDRAWAL, amount = BigDecimal(krw), currency = "KRW", amountKrw = BigDecimal(krw), memo = "출금",
    )
    private fun trade(day: Int, type: String, name: String, total: String, fee: String, tax: String) =
        TradeCashRecord(LocalDate.of(2026, 6, day), type, name, "한투", BigDecimal(total), BigDecimal(fee), BigDecimal(tax))
    private fun account() = Account.reconstruct(
        id = acctId, userId = userId, provider = AccountProvider.KIS, accountType = AccountType.STOCK,
        accountName = "한투", externalId = null, currency = "KRW", status = AccountStatus.ACTIVE,
        lastSyncedAt = null, createdAt = LocalDateTime.now(), apiKey = null, apiSecret = null,
        walletAddress = null, chain = null,
    )

    private fun generator(flows: List<CashFlow>, trades: List<TradeCashRecord>) =
        CashflowReportGenerator(FakeCashFlowRepo(flows), FakeTradeSource(trades), FakeAccountRepo(listOf(account())))

    private fun standardFlows() = listOf(deposit(1, "1000000"), withdrawal(20, "300000"))
    private fun standardTrades() = listOf(
        trade(5, "BUY", "삼성전자", "5000000", "1500", "0"),
        trade(25, "SELL", "삼성전자", "2000000", "800", "3000"),
        trade(10, "DIVIDEND", "삼성전자", "50000", "0", "7700"),
    )

    @Test
    fun `summary inflow outflow netflow`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val s = body["summary"]
        assertEquals(3050000.0, s["totalInflow"].asDouble(), 0.01)
        assertEquals(5313000.0, s["totalOutflow"].asDouble(), 0.01)
        assertEquals(-2263000.0, s["netFlow"].asDouble(), 0.01)
    }

    @Test
    fun `byType signs and directions`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val byType = body["byType"].associate { it["type"].asText() to it }
        assertEquals(1000000.0, byType["입금"]!!["amount"].asDouble(), 0.01)
        assertEquals("IN", byType["입금"]!!["direction"].asText())
        assertEquals(2000000.0, byType["매도대금"]!!["amount"].asDouble(), 0.01)
        assertEquals(50000.0, byType["배당·이자"]!!["amount"].asDouble(), 0.01)
        assertEquals(-300000.0, byType["출금"]!!["amount"].asDouble(), 0.01)
        assertEquals(-5000000.0, byType["매수대금"]!!["amount"].asDouble(), 0.01)
        assertEquals(-13000.0, byType["수수료·세금"]!!["amount"].asDouble(), 0.01)
        assertEquals("OUT", byType["수수료·세금"]!!["direction"].asText())
    }

    @Test
    fun `dividend classified as dividend not sell`() {
        val body = mapper.readTree(generator(emptyList(), listOf(trade(10, "DIVIDEND", "삼성전자", "50000", "0", "0"))).generate(userId, period).bodyJson)
        val types = body["byType"].map { it["type"].asText() }.toSet()
        assertTrue(types.contains("배당·이자"))
        assertTrue(!types.contains("매도대금"))
    }

    @Test
    fun `monthly aggregation consistent with summary`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        assertEquals(1, body["monthly"].size())
        val m = body["monthly"][0]
        assertEquals("2026-06", m["month"].asText())
        assertEquals(3050000.0, m["inflow"].asDouble(), 0.01)
        assertEquals(5313000.0, m["outflow"].asDouble(), 0.01)
        assertEquals(-2263000.0, m["net"].asDouble(), 0.01)
    }

    @Test
    fun `details merge flows and trades sorted by date with signs`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val d = body["details"]
        assertEquals(5, d.size())
        assertEquals("2026-06-01", d[0]["date"].asText())
        assertEquals(1000000.0, d[0]["amount"].asDouble(), 0.01)
        assertEquals("2026-06-05", d[1]["date"].asText())
        assertEquals(-5000000.0, d[1]["amount"].asDouble(), 0.01)
    }

    @Test
    fun `empty yields valid zero report`() {
        val generated = generator(emptyList(), emptyList()).generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["summary"]["netFlow"].asDouble(), 0.01)
        assertEquals(0, body["details"].size())
        assertTrue(body["byType"].isEmpty)
    }
}
