package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.CashflowTradeSource
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
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

    private class FakeAssetRepo(private val assets: List<Asset>) : AssetRepository {
        override fun save(asset: Asset) = asset
        override fun saveAll(assets: List<Asset>) = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByAccountId(accountId: UUID) = assets
        override fun findByUserId(userId: UUID) = assets
        override fun deleteByAccountId(accountId: UUID) {}
        override fun delete(id: UUID) {}
    }
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1000")
    }
    private fun cashAsset(krw: String) = Asset.create(
        userId = userId, accountId = acctId, category = AssetCategory.FINANCIAL, type = AssetType.CASH,
        sourceType = AssetSourceType.STOCK_API, name = "현금", symbol = null,
        quantity = BigDecimal.ONE, purchasePrice = BigDecimal(krw), currentValue = BigDecimal(krw),
        currency = "KRW", valuationMethod = ValuationMethod.MARKET_PRICE,
    )
    private fun flowOn(date: LocalDate, type: FlowType, krw: String) = CashFlow.create(
        userId = userId, accountId = acctId, flowDate = date, type = type,
        amount = BigDecimal(krw), currency = "KRW", amountKrw = BigDecimal(krw), memo = "x",
    )
    private fun tradeOn(date: LocalDate, type: String, total: String) =
        TradeCashRecord(date, type, "n", "한투", BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO)

    private fun generator(flows: List<CashFlow>, trades: List<TradeCashRecord>, cashAssets: List<Asset> = emptyList()) =
        CashflowReportGenerator(FakeCashFlowRepo(flows), FakeTradeSource(trades), FakeAccountRepo(listOf(account())), FakeAssetRepo(cashAssets), fx)

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
        assertEquals("한투", d[1]["account"].asText())
        assertEquals("삼성전자", d[1]["description"].asText())
        // sorted tail: 06-10 배당 → 06-20 출금 → 06-25 매도
        assertEquals("2026-06-20", d[3]["date"].asText())
        assertEquals(-300000.0, d[3]["amount"].asDouble(), 0.01)  // 출금 음수
        assertEquals("2026-06-25", d[4]["date"].asText())
        assertEquals("매도대금", d[4]["type"].asText())
        assertEquals(2000000.0, d[4]["amount"].asDouble(), 0.01)
    }

    @Test
    fun `byType omits zero-amount types`() {
        val body = mapper.readTree(generator(listOf(deposit(1, "1000000")), emptyList()).generate(userId, period).bodyJson)
        val types = body["byType"].map { it["type"].asText() }.toSet()
        assertTrue(types.contains("입금"))
        assertTrue(!types.contains("출금"))
        assertTrue(!types.contains("매수대금"))
        assertEquals(1, body["byType"].size())
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

    @Test
    fun `기초잔고는 기간 이전 이력에서 재구성되고 기말은 기초 더하기 순흐름이다`() {
        val before = listOf(flowOn(LocalDate.of(2026, 5, 10), FlowType.DEPOSIT, "100000"))
        val beforeT = listOf(tradeOn(LocalDate.of(2026, 5, 15), "BUY", "40000"))
        val periodFlows = before + deposit(2, "50000") // 6월 입금 +50000
        val body = mapper.readTree(generator(periodFlows, beforeT).generate(userId, period).bodyJson)
        val r = body["reconciliation"]
        assertEquals(60000.0, r["openingBalance"].asDouble(), 0.01)      // 100000 - 40000
        assertEquals(110000.0, r["closingCalculated"].asDouble(), 0.01)  // 60000 + 50000(netFlow)
    }

    @Test
    fun `기말 계산이 실제 현금과 일치하고 이후 활동 없으면 정합된다`() {
        val body = mapper.readTree(
            generator(listOf(deposit(2, "200000")), emptyList(), cashAssets = listOf(cashAsset("200000")))
                .generate(userId, period).bodyJson,
        )
        val r = body["reconciliation"]
        assertEquals(200000.0, r["closingCalculated"].asDouble(), 0.01)
        assertEquals(200000.0, r["actualCash"].asDouble(), 0.01)
        assertEquals(0.0, r["difference"].asDouble(), 0.01)
        assertTrue(r["reconcilable"].asBoolean())
        assertTrue(r["reconciled"].asBoolean())
    }

    @Test
    fun `실제 현금이 계산 기말과 다르면 정합 실패하고 차액이 표시된다`() {
        val body = mapper.readTree(
            generator(listOf(deposit(2, "200000")), emptyList(), cashAssets = listOf(cashAsset("250000")))
                .generate(userId, period).bodyJson,
        )
        val r = body["reconciliation"]
        assertEquals(50000.0, r["difference"].asDouble(), 0.01) // 실제 250000 - 계산 200000
        assertTrue(r["reconcilable"].asBoolean())
        assertEquals(false, r["reconciled"].asBoolean())
    }

    @Test
    fun `기간 이후 현금활동이 있으면 reconcilable false 이다`() {
        val flows = listOf(deposit(2, "200000"), flowOn(LocalDate.of(2026, 7, 5), FlowType.DEPOSIT, "10000"))
        val body = mapper.readTree(
            generator(flows, emptyList(), cashAssets = listOf(cashAsset("210000"))).generate(userId, period).bodyJson,
        )
        val r = body["reconciliation"]
        assertEquals(false, r["reconcilable"].asBoolean())
        assertEquals(false, r["reconciled"].asBoolean())
    }

    @Test
    fun `비원화 현금은 fx로 환산돼 실제 현금에 반영된다`() {
        // 6월 입금 200000 → 기말 200000. 실제현금 = USD 200 → ×1000 = 200000 → 정합.
        val usdCash = Asset.create(
            userId = userId, accountId = acctId, category = AssetCategory.FINANCIAL, type = AssetType.CASH,
            sourceType = AssetSourceType.STOCK_API, name = "USD현금", symbol = null,
            quantity = BigDecimal.ONE, purchasePrice = BigDecimal("200"), currentValue = BigDecimal("200"),
            currency = "USD", valuationMethod = ValuationMethod.MARKET_PRICE,
        )
        val body = mapper.readTree(
            generator(listOf(deposit(2, "200000")), emptyList(), cashAssets = listOf(usdCash)).generate(userId, period).bodyJson,
        )
        val r = body["reconciliation"]
        assertEquals(200000.0, r["actualCash"].asDouble(), 0.01) // 200 USD × 1000
        assertTrue(r["reconciled"].asBoolean())
    }

    @Test
    fun `기초 재구성은 출금 매도 배당 부호를 모두 반영한다`() {
        val beforeFlows = listOf(flowOn(LocalDate.of(2026, 5, 3), FlowType.WITHDRAWAL, "30000"))
        val beforeTrades = listOf(
            tradeOn(LocalDate.of(2026, 5, 4), "SELL", "100000"),
            tradeOn(LocalDate.of(2026, 5, 5), "DIVIDEND", "5000"),
        )
        val body = mapper.readTree(generator(beforeFlows, beforeTrades).generate(userId, period).bodyJson)
        // opening = -30000 + 100000 + 5000 = 75000, 기간 흐름 없음 → 기말 동일
        assertEquals(75000.0, body["reconciliation"]["openingBalance"].asDouble(), 0.01)
        assertEquals(75000.0, body["reconciliation"]["closingCalculated"].asDouble(), 0.01)
    }

    @Test
    fun `조정표 항등식 - 기초 더하기 증감합 등 기말`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val r = body["reconciliation"]
        val opening = r["openingBalance"].asDouble()
        val changesSum = r["changes"].sumOf { it["amount"].asDouble() }
        assertEquals(r["closingCalculated"].asDouble(), opening + changesSum, 0.01)
    }

    @Test
    fun `특이거래 - 대규모 이동과 미분류 흐름`() {
        val flows = listOf(deposit(2, "150000"))
        val trades = listOf(tradeOn(LocalDate.of(2026, 6, 5), "MARGIN", "10000"))
        val body = mapper.readTree(
            generator(flows, trades, cashAssets = listOf(cashAsset("1000000"))).generate(userId, period).bodyJson,
        )
        val st = body["specialTransactions"]
        assertEquals(1, st["largeMovements"].size())
        assertEquals(150000.0, st["largeMovements"][0]["amountKrw"].asDouble(), 0.01)
        assertEquals(1, st["unclassified"].size())
        assertEquals("MARGIN", st["unclassified"][0]["tradeType"].asText())
    }

    @Test
    fun `특이거래 - 총자산 없으면 대규모 이동 비어있다`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val st = body["specialTransactions"]
        assertEquals(0, st["largeMovements"].size())
    }

    @Test
    fun `내부유형(이체·환전)은 외부흐름 집계·상세에서 제외된다`() {
        val flows = listOf(
            deposit(3, "1000000"),
            withdrawal(5, "200000"),
            flowOn(LocalDate.of(2026, 6, 10), FlowType.TRANSFER_OUT, "5000000"),
            flowOn(LocalDate.of(2026, 6, 10), FlowType.TRANSFER_IN, "5000000"),
            flowOn(LocalDate.of(2026, 6, 11), FlowType.FX_OUT, "1300000"),
            flowOn(LocalDate.of(2026, 6, 11), FlowType.FX_IN, "1300000"),
        )
        val body = mapper.readTree(generator(flows, emptyList()).generate(userId, period).bodyJson)
        // byType 유입/유출에 이체·환전 미포함 (입금 1,000,000 / 출금 200,000 만)
        val types = body["byType"].associate { it["type"].asText() to it["amount"].asDouble() }
        assertEquals(1000000.0, types["입금"] ?: 0.0, 0.01)
        assertEquals(-200000.0, types["출금"] ?: 0.0, 0.01)
        assertTrue(types.keys.none { it.contains("이체") || it.contains("환전") })
        // details에 내부유형 레그가 "출금"으로 잘못 들어가지 않음: 출금 행은 1건(withdrawal)만
        val outRows = body["details"].filter { it["type"].asText() == "출금" }
        assertEquals(1, outRows.size)
    }
}
