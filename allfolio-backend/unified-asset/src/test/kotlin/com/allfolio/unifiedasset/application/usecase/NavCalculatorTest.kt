package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class NavCalculatorTest {

    /** KRW 1:1, USD → 1300원 고정 환율 페이크. */
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            when (currency.uppercase()) {
                "KRW" -> amount
                "USD", "USDT" -> amount.multiply(BigDecimal("1300"))
                else -> amount
            }

        override fun rateOf(currency: String): BigDecimal =
            when (currency.uppercase()) {
                "USD", "USDT" -> BigDecimal("1300")
                else -> BigDecimal.ONE
            }
    }

    @Test
    fun `navInKrw converts each asset to KRW before summing a mixed KRW+USD portfolio`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        // KIS 국내주식(원화) 1,000,000원 + Binance 보유(USD) 1,000달러
        val krwStock = asset(userId, accountId, currentValue = BigDecimal("1000000"), currency = "KRW")
        val usdCrypto = asset(userId, accountId, currentValue = BigDecimal("1000"), currency = "USD")

        val nav = listOf(krwStock, usdCrypto).navInKrw(fx)

        // 1,000,000 + (1,000 * 1,300) = 2,300,000
        assertEquals(0, BigDecimal("2300000").compareTo(nav)) { "expected 2,300,000 KRW but was $nav" }
    }

    @Test
    fun `navInKrw of an empty portfolio is zero`() {
        assertEquals(0, BigDecimal.ZERO.compareTo(emptyList<Asset>().navInKrw(fx)))
    }

    private fun asset(
        userId: UUID,
        accountId: UUID,
        currentValue: BigDecimal,
        currency: String,
    ): Asset = Asset.create(
        userId = userId,
        accountId = accountId,
        category = AssetCategory.FINANCIAL,
        type = AssetType.STOCK,
        sourceType = AssetSourceType.STOCK_API,
        name = "test-$currency",
        symbol = null,
        quantity = BigDecimal.ONE,
        purchasePrice = currentValue,
        currentValue = currentValue,
        currency = currency,
        valuationMethod = ValuationMethod.BALANCE,
    )
}
