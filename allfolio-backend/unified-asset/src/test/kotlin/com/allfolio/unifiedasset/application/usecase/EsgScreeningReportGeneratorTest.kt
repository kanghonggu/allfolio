package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
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
    }

    private fun asset(name: String, symbol: String, current: String, currency: String = "KRW", type: AssetType = AssetType.STOCK) =
        Asset.create(
            userId = userId, accountId = acctId, category = AssetCategory.FINANCIAL,
            type = type, sourceType = AssetSourceType.STOCK_API, name = name, symbol = symbol,
            quantity = BigDecimal.ONE, purchasePrice = BigDecimal(current), currentValue = BigDecimal(current),
            currency = currency, valuationMethod = ValuationMethod.MARKET_PRICE,
        )

    private fun generator(assets: List<Asset>) = EsgScreeningReportGenerator(FakeAssetRepo(assets), fx)

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
}
