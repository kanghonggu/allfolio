package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class HoldingsReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val acctA = UUID.randomUUID()
    private val acctB = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeAssetRepo(private val assets: List<Asset>) : AssetRepository {
        override fun save(asset: Asset) = asset
        override fun saveAll(assets: List<Asset>) = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByAccountId(accountId: UUID) = assets
        override fun findByUserId(userId: UUID) = assets
        override fun deleteByAccountId(accountId: UUID) {}
        override fun delete(id: UUID) {}
    }
    private class FakeAccountRepo(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account) = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID) = accounts
        override fun findByProviders(providers: Collection<AccountProvider>) = emptyList<Account>()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: AccountStatus) {}
    }
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1000")
    }

    private class FakeStockTradeRepo(private val trades: List<StockTrade>) : StockTradeRepository {
        override fun save(trade: StockTrade) = trade
        override fun findByAccountId(accountId: UUID) = trades.filter { it.accountId == accountId }
        override fun findById(id: UUID): StockTrade? = null
        override fun delete(id: UUID) {}
    }

    private fun stockTrade(accountId: UUID, type: StockTradeType, symbol: String, qty: String, price: String, on: LocalDate) =
        StockTrade.create(
            accountId = accountId, userId = userId, tradeType = type, stockName = symbol, symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal(price),
            totalAmount = BigDecimal(qty).multiply(BigDecimal(price)), tradedAt = on, memo = null,
        )

    private fun asset(accountId: UUID, name: String, type: AssetType, qty: String, purchase: String, current: String, currency: String = "KRW") =
        Asset.create(
            userId = userId, accountId = accountId, category = AssetCategory.FINANCIAL,
            type = type, sourceType = AssetSourceType.STOCK_API, name = name, symbol = name,
            quantity = BigDecimal(qty), purchasePrice = BigDecimal(purchase),
            currentValue = BigDecimal(current), currency = currency,
            valuationMethod = ValuationMethod.MARKET_PRICE,
        )
    private fun account(id: UUID, name: String, provider: AccountProvider) = Account.reconstruct(
        id = id, userId = userId, provider = provider, accountType = AccountType.STOCK,
        accountName = name, externalId = null, currency = "KRW", status = AccountStatus.ACTIVE,
        lastSyncedAt = null, createdAt = LocalDateTime.now(), apiKey = null, apiSecret = null,
        walletAddress = null, chain = null,
    )

    private fun generator(assets: List<Asset>, accounts: List<Account>, trades: List<StockTrade> = emptyList()) =
        HoldingsReportGenerator(FakeAssetRepo(assets), FakeAccountRepo(accounts), fx, FakeStockTradeRepo(trades))

    private fun standardAssets() = listOf(
        asset(acctA, "삼성전자", AssetType.STOCK, "1", "7000000", "8000000"),
        asset(acctA, "Apple", AssetType.STOCK, "1", "4000", "5000", "USD"),
        asset(acctB, "원화예수금", AssetType.CASH, "1", "3000000", "3000000"),
    )
    private fun standardAccounts() = listOf(
        account(acctA, "한국투자", AccountProvider.KIS),
        account(acctB, "은행", AccountProvider.MANUAL),
    )

    @Test
    fun `summary aggregates total value count pnl cashWeight`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val s = body["summary"]
        assertEquals(16000000.0, s["totalValueKrw"].asDouble(), 0.01)
        assertEquals(3, s["holdingCount"].asInt())
        assertEquals(2, s["accountCount"].asInt())
        assertEquals(2000000.0, s["unrealizedPnlKrw"].asDouble(), 0.01)
        assertEquals(18.75, s["cashWeight"].asDouble(), 0.01)
    }

    @Test
    fun `holdings sorted by valueKrw desc with fields`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val h = body["holdings"]
        assertEquals(3, h.size())
        assertEquals("삼성전자", h[0]["name"].asText())
        assertEquals(8000000.0, h[0]["valueKrw"].asDouble(), 0.01)
        assertEquals(1000000.0, h[0]["unrealizedPnl"].asDouble(), 0.01)
        assertEquals(14.29, h[0]["returnRate"].asDouble(), 0.01)
    }

    @Test
    fun `byType groups with weights summing to 100`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val types = body["byType"].associate { it["type"].asText() to it }
        assertEquals(13000000.0, types["STOCK"]!!["valueKrw"].asDouble(), 0.01)
        assertEquals(2, types["STOCK"]!!["holdingCount"].asInt())
        assertEquals(3000000.0, types["CASH"]!!["valueKrw"].asDouble(), 0.01)
        assertEquals(100.0, body["byType"].sumOf { it["weight"].asDouble() }, 0.1)
    }

    @Test
    fun `byAccount subtotals with weight`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val kis = body["byAccount"].first { it["account"].asText() == "한국투자" }
        assertEquals(13000000.0, kis["valueKrw"].asDouble(), 0.01)
        assertEquals(2, kis["holdingCount"].asInt())
        assertEquals(81.25, kis["weight"].asDouble(), 0.01)
        assertEquals("KIS", kis["provider"].asText())
    }

    @Test
    fun `usd holding keeps native currency values and converts valueKrw`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val apple = body["holdings"].first { it["name"].asText() == "Apple" }
        assertEquals(5000.0, apple["currentValue"].asDouble(), 0.01)   // 원통화(USD) 유지
        assertEquals(4000.0, apple["avgPrice"].asDouble(), 0.01)        // 원통화(USD) 유지
        assertEquals(5000000.0, apple["valueKrw"].asDouble(), 0.01)     // ×1000 환산
        assertEquals(31.25, apple["weight"].asDouble(), 0.01)          // 5M/16M
        assertEquals(25.0, apple["returnRate"].asDouble(), 0.01)       // 1000/4000×100
    }

    @Test
    fun `cash section lists CASH assets`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        assertEquals(1, body["cash"].size())
        assertEquals(3000000.0, body["cash"][0]["valueKrw"].asDouble(), 0.01)
        assertEquals("KRW", body["cash"][0]["currency"].asText())
    }

    @Test
    fun `zero assets yields valid empty report`() {
        val generated = generator(emptyList(), emptyList()).generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["summary"]["totalValueKrw"].asDouble(), 0.01)
        assertEquals(0, body["holdings"].size())
        assertEquals(0.0, body["summary"]["cashWeight"].asDouble(), 0.01)
    }

    @Test
    fun `당월 실현손익이 요약과 realized 섹션에 반영된다`() {
        val accId = acctA
        val trades = listOf(
            stockTrade(accId, StockTradeType.BUY, "ZZZ", "10", "100", LocalDate.of(2026, 6, 5)),
            stockTrade(accId, StockTradeType.SELL, "ZZZ", "4", "150", LocalDate.of(2026, 6, 20)),
        )
        val body = mapper.readTree(
            generator(standardAssets(), standardAccounts(), trades).generate(userId, period).bodyJson,
        )
        assertEquals(200.0, body["summary"]["realizedPnlKrw"].asDouble(), 0.01)
        val realized = body["realized"]
        assertEquals(1, realized.size())
        assertEquals("ZZZ", realized[0]["symbol"].asText())
        assertEquals(200.0, realized[0]["realizedPnl"].asDouble(), 0.01)
    }

    @Test
    fun `거래 없으면 실현손익 0이고 realized 섹션 비어있다`() {
        val body = mapper.readTree(
            generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson,
        )
        assertEquals(0.0, body["summary"]["realizedPnlKrw"].asDouble(), 0.01)
        assertEquals(0, body["realized"].size())
        assertEquals(0.0, body["holdings"][0]["realizedPnl"].asDouble(), 0.01)
    }
}
