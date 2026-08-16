package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.ExclusionListRepository
import com.allfolio.unifiedasset.application.port.ExclusionPresetRepository
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
import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import com.allfolio.unifiedasset.domain.exclusion.ExclusionPreset
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class EsgScreeningReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val acctId = UUID.randomUUID()
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
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1000")

        override fun rateOf(currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") BigDecimal.ONE else BigDecimal("1000")
    }

    private fun asset(name: String, symbol: String, current: String, currency: String = "KRW", type: AssetType = AssetType.STOCK) =
        Asset.create(
            userId = userId, accountId = acctId, category = AssetCategory.FINANCIAL,
            type = type, sourceType = AssetSourceType.STOCK_API, name = name, symbol = symbol,
            quantity = BigDecimal.ONE, purchasePrice = BigDecimal(current), currentValue = BigDecimal(current),
            currency = currency, valuationMethod = ValuationMethod.MARKET_PRICE,
        )

    private class FakePresetRepo(private val presets: List<ExclusionPreset>) : ExclusionPresetRepository {
        override fun findAll() = presets
        override fun findBySymbol(symbol: String) = presets.firstOrNull { it.symbol == symbol }
        override fun save(preset: ExclusionPreset) = preset
        override fun delete(id: UUID) {}
    }

    private fun defaultPresets(): List<ExclusionPreset> {
        val now = LocalDateTime.now()
        return listOf(
            ExclusionPreset(UUID.randomUUID(), "EXCL-COAL-01", "예시 프리셋", "석탄", null, now, now),
            ExclusionPreset(UUID.randomUUID(), "EXCL-WEAPON-01", "예시 프리셋", "논란무기", null, now, now),
        )
    }

    private class FakeExclusionRepo(private val lists: List<ExclusionList>) : ExclusionListRepository {
        override fun findByUser(userId: UUID) = lists.filter { it.userId == userId }
        override fun findActiveByUser(userId: UUID) = lists.filter { it.userId == userId && it.active }
        override fun findById(id: UUID) = lists.firstOrNull { it.id == id }
        override fun saveList(list: ExclusionList) = list
        override fun deleteList(id: UUID) {}
        override fun addItem(item: ExclusionItem) = item
        override fun deleteItem(itemId: UUID) {}
        override fun existsItem(listId: UUID, symbol: String) = false
    }

    private fun userList(active: Boolean, owner: UUID, vararg symbols: String): ExclusionList {
        val lid = UUID.randomUUID()
        val now = LocalDateTime.now()
        return ExclusionList(lid, owner, "내 리스트", "사용자지정", null, active, now, now,
            symbols.map { ExclusionItem(UUID.randomUUID(), lid, it, null, now) })
    }

    private class FakeAccountRepo(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account) = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID) = accounts
        override fun findByProviders(providers: Collection<AccountProvider>) = emptyList<Account>()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: AccountStatus) {}
    }
    private class FakeStockTradeRepo(private val trades: List<StockTrade>) : StockTradeRepository {
        override fun save(trade: StockTrade) = trade
        override fun findByAccountId(accountId: UUID) = trades.filter { it.accountId == accountId }
        override fun findById(id: UUID): StockTrade? = null
        override fun delete(id: UUID) {}
        override fun deleteByAccountId(accountId: UUID) {}
    }
    private fun account() = Account.reconstruct(
        id = acctId, userId = userId, provider = AccountProvider.KIS, accountType = AccountType.STOCK,
        accountName = "한투", externalId = null, currency = "KRW", status = AccountStatus.ACTIVE,
        lastSyncedAt = null, createdAt = LocalDateTime.now(), apiKey = null, apiSecret = null,
        walletAddress = null, chain = null,
    )
    private fun stockTrade(type: StockTradeType, symbol: String, qty: String, on: LocalDate) =
        StockTrade.create(
            accountId = acctId, userId = userId, tradeType = type, stockName = symbol, symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal.ONE, totalAmount = BigDecimal(qty), tradedAt = on, memo = null,
        )

    private fun generator(
        assets: List<Asset>,
        exclusion: ExclusionListRepository = FakeExclusionRepo(emptyList()),
        accounts: List<Account> = emptyList(),
        trades: List<StockTrade> = emptyList(),
        presets: List<ExclusionPreset> = defaultPresets(),
    ) = EsgScreeningReportGenerator(
        FakeAssetRepo(assets), fx, exclusion, FakeAccountRepo(accounts), FakeStockTradeRepo(trades), FakePresetRepo(presets),
    )

    private fun standardAssets() = listOf(
        asset("삼성전자", "005930", "8000000"),
        asset("Apple", "AAPL", "5000", "USD"),
        asset("석탄기업", "EXCL-COAL-01", "2000000"),
    )

    @Test
    fun `esg score reuses EsgEngine`() {
        val body = mapper.readTree(generator(standardAssets()).generate(userId, period).bodyJson)
        val esg = body["esg"]
        assertEquals(63.25, esg["totalScore"].asDouble(), 0.01)
        assertEquals("B", esg["rating"].asText())
        assertEquals(60.0, esg["environmental"].asDouble(), 0.01)
        assertEquals(65.0, esg["social"].asDouble(), 0.01)
        assertEquals(3, body["esgBreakdown"].size())
        assertEquals("삼성전자", body["esgBreakdown"][0]["name"].asText())
    }

    @Test
    fun `breakdown weight is 0 to 100 scale`() {
        val body = mapper.readTree(generator(standardAssets()).generate(userId, period).bodyJson)
        val bd = body["esgBreakdown"]
        assertEquals(53.33, bd[0]["weight"].asDouble(), 0.01)
        assertEquals(100.0, bd.sumOf { it["weight"].asDouble() }, 0.1)
    }

    @Test
    fun `screening flags preset symbol as violation`() {
        val body = mapper.readTree(generator(standardAssets()).generate(userId, period).bodyJson)
        assertEquals(1, body["screening"]["violationCount"].asInt())
        assertEquals(2000000.0, body["screening"]["violationValueKrw"].asDouble(), 0.01)
        assertEquals(13.33, body["screening"]["violationWeight"].asDouble(), 0.01)
        val v = body["violations"][0]
        assertEquals("EXCL-COAL-01", v["symbol"].asText())
        assertEquals("석탄", v["reason"].asText())
        assertEquals(2000000.0, v["valueKrw"].asDouble(), 0.01)
    }

    @Test
    fun `no violation when no preset symbol held`() {
        val body = mapper.readTree(generator(listOf(asset("삼성전자", "005930", "8000000"))).generate(userId, period).bodyJson)
        assertEquals(0, body["screening"]["violationCount"].asInt())
        assertEquals(0, body["violations"].size())
        assertEquals(0.0, body["screening"]["violationWeight"].asDouble(), 0.01)
    }

    @Test
    fun `usd asset converted for violation value`() {
        val body = mapper.readTree(generator(listOf(asset("해외석탄", "EXCL-COAL-01", "2000", "USD"))).generate(userId, period).bodyJson)
        assertEquals(2000000.0, body["screening"]["violationValueKrw"].asDouble(), 0.01)
    }

    @Test
    fun `empty assets yields valid zero report`() {
        val generated = generator(emptyList()).generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["esg"]["totalScore"].asDouble(), 0.01)
        assertEquals(0, body["esgBreakdown"].size())
        assertEquals(0, body["screening"]["violationCount"].asInt())
        assertTrue(body["violations"].isEmpty)
    }

    @Test
    fun `breakdown sorted by esg total not value`() {
        val assets = listOf(
            asset("비트코인", "BTC", "10000000", type = AssetType.CRYPTO),  // total 36, 값 큼
            asset("현금", "KRW-CASH", "1000000", type = AssetType.CASH),      // total 78.5, 값 작음
        )
        val body = mapper.readTree(generator(assets).generate(userId, period).bodyJson)
        val bd = body["esgBreakdown"]
        assertEquals("현금", bd[0]["name"].asText())        // total 78.5 최상위
        assertEquals(78.5, bd[0]["total"].asDouble(), 0.01)
        assertEquals("비트코인", bd[1]["name"].asText())     // total 36
    }

    @Test
    fun `user active list flags a held symbol as violation`() {
        val assets = listOf(asset("삼성전자", "005930", "8000000"))
        val repo = FakeExclusionRepo(listOf(userList(true, userId, "005930")))
        val body = mapper.readTree(generator(assets, repo).generate(userId, period).bodyJson)
        assertEquals(1, body["screening"]["violationCount"].asInt())
        assertEquals("005930", body["violations"][0]["symbol"].asText())
    }

    @Test
    fun `inactive user list is ignored`() {
        val assets = listOf(asset("삼성전자", "005930", "8000000"))
        val repo = FakeExclusionRepo(listOf(userList(false, userId, "005930")))
        val body = mapper.readTree(generator(assets, repo).generate(userId, period).bodyJson)
        assertEquals(0, body["screening"]["violationCount"].asInt())
    }

    @Test
    fun `other users list is ignored`() {
        val assets = listOf(asset("삼성전자", "005930", "8000000"))
        val repo = FakeExclusionRepo(listOf(userList(true, UUID.randomUUID(), "005930")))
        val body = mapper.readTree(generator(assets, repo).generate(userId, period).bodyJson)
        assertEquals(0, body["screening"]["violationCount"].asInt())
    }

    @Test
    fun `위반 종목에 편입일과 배지가 붙고 위반이력 섹션이 생긴다`() {
        val assets = listOf(asset("종목Z", "ZZZ", "1000000"))
        val list = userList(active = true, owner = userId, "ZZZ")
        val trades = listOf(stockTrade(StockTradeType.BUY, "ZZZ", "10", LocalDate.of(2026, 6, 5)))
        val body = mapper.readTree(
            generator(assets, FakeExclusionRepo(listOf(list)), listOf(account()), trades).generate(userId, period).bodyJson,
        )
        val v = body["violations"].first { it["symbol"].asText() == "ZZZ" }
        assertEquals("2026-06-05", v["firstBuyDate"].asText())
        assertTrue(v["sinceListed"].asText().isNotEmpty())
        val hist = body["violationHistory"]
        assertTrue(hist.any { it["symbol"].asText() == "ZZZ" && it["event"].asText() == "편입" })
    }

    @Test
    fun `거래가 없으면 위반이력은 비어있다`() {
        val assets = listOf(asset("종목Z", "ZZZ", "1000000"))
        val list = userList(active = true, owner = userId, "ZZZ")
        val body = mapper.readTree(
            generator(assets, FakeExclusionRepo(listOf(list))).generate(userId, period).bodyJson,
        )
        assertTrue(body.has("violationHistory"))
    }
}
