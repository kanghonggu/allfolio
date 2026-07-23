package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.DividendLedgerSource
import com.allfolio.unifiedasset.application.port.DividendRecord
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

class DividendInterestReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeLedger(private val all: List<DividendRecord>) : DividendLedgerSource {
        override fun findDividends(userId: UUID, from: LocalDate, to: LocalDate) =
            all.filter { it.payDate in from..to }.sortedBy { it.payDate }
    }

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

    private fun asset(valueKrw: String) = Asset.create(
        userId = userId, accountId = accountId, category = AssetCategory.FINANCIAL,
        type = AssetType.STOCK, sourceType = AssetSourceType.STOCK_API, name = "보유", symbol = "005930",
        quantity = BigDecimal.ONE, purchasePrice = BigDecimal(valueKrw),
        currentValue = BigDecimal(valueKrw), currency = "KRW",
        valuationMethod = ValuationMethod.BALANCE,
    )

    private fun rec(day: Int, name: String, symbol: String?, gross: String, tax: String) =
        DividendRecord(LocalDate.of(2026, 6, day), name, symbol, "한투", "KIS", BigDecimal(gross), BigDecimal(tax))

    private fun generator(
        records: List<DividendRecord>,
        assets: List<Asset> = listOf(asset("100000000")),
    ) = DividendInterestReportGenerator(FakeLedger(records), FakeAssetRepo(assets), fx)

    @Test
    fun `summary aggregates gross tax net and effective rate`() {
        val gen = generator(listOf(
            rec(3, "삼성전자", "005930", "10000", "1540"),
            rec(20, "AAPL", "AAPL", "20000", "3000"),
        ))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val s = body["summary"]
        assertEquals(30000.0, s["grossTotal"].asDouble(), 0.01)
        assertEquals(4540.0, s["withholdingTax"].asDouble(), 0.01)
        assertEquals(25460.0, s["netTotal"].asDouble(), 0.01)
        assertEquals(2, s["receiptCount"].asInt())
        assertEquals(15.13, s["effectiveTaxRate"].asDouble(), 0.01)
    }

    @Test
    fun `receipt net equals gross minus tax`() {
        val gen = generator(listOf(rec(3, "삼성전자", "005930", "10000", "1540")))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val r0 = body["receipts"][0]
        assertEquals(10000.0, r0["gross"].asDouble(), 0.01)
        assertEquals(1540.0, r0["tax"].asDouble(), 0.01)
        assertEquals(8460.0, r0["net"].asDouble(), 0.01)
    }

    @Test
    fun `bySymbol weights sum to about 100`() {
        val gen = generator(listOf(
            rec(3, "삼성전자", "005930", "10000", "0"),
            rec(20, "AAPL", "AAPL", "30000", "0"),
        ))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val sum = body["bySymbol"].sumOf { it["weight"].asDouble() }
        assertEquals(100.0, sum, 0.1)
    }

    @Test
    fun `monthly aggregates net by year-month`() {
        val gen = generator(listOf(
            rec(3, "삼성전자", "005930", "10000", "1000"),
            rec(20, "삼성전자", "005930", "5000", "500"),
        ))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        assertEquals(1, body["monthly"].size())
        assertEquals("2026-06", body["monthly"][0]["month"].asText())
        assertEquals(13500.0, body["monthly"][0]["net"].asDouble(), 0.01)
    }

    @Test
    fun `byCountry buckets numeric ticker as domestic and alpha as overseas`() {
        val gen = generator(listOf(
            rec(3, "삼성전자", "005930", "10000", "1540"),
            rec(20, "AAPL", "AAPL", "20000", "3000"),
        ))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val countries = body["byCountry"].map { it["country"].asText() }.toSet()
        assertEquals(setOf("국내", "해외"), countries)
    }

    @Test
    fun `zero dividends yields valid empty report without exception`() {
        val gen = generator(emptyList())
        val generated = gen.generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["summary"]["grossTotal"].asDouble(), 0.01)
        assertEquals(0, body["receipts"].size())
        assertEquals(0.0, body["summary"]["ttmYield"].asDouble(), 0.01)
    }

    @Test
    fun `ttm yield is a number when assets exist`() {
        val gen = generator(listOf(rec(3, "삼성전자", "005930", "10000", "1540")))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        assertTrue(body["summary"]["ttmYield"].isNumber)
    }

    @Test
    fun `null ttm yield when portfolio value is zero`() {
        val gen = generator(listOf(rec(3, "삼성전자", "005930", "10000", "1540")), assets = emptyList())
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        assertTrue(body["summary"]["ttmYield"].isNull)
    }
}
