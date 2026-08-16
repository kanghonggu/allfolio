package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetLiquidityType
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class NavByCurrencyTest {

    /** KRW 1:1, USD → 1300원 고정 환율 페이크. */
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            when (currency.trim().uppercase()) {
                "KRW" -> amount
                "USD", "USDT" -> amount.multiply(BigDecimal("1300"))
                else -> amount
            }

        override fun rateOf(currency: String): BigDecimal =
            when (currency.trim().uppercase()) {
                "USD", "USDT" -> BigDecimal("1300")
                else -> BigDecimal.ONE
            }
    }

    @Test
    fun `같은 통화 자산이 하나로 묶인다`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        val usd1 = asset(userId, accountId, currentValue = BigDecimal("100"), currency = "USD")
        val usd2 = asset(userId, accountId, currentValue = BigDecimal("250"), currency = "USD")

        val byCurrency = listOf(usd1, usd2).navByCurrency()

        assertEquals(1, byCurrency.size)
        assertEquals(0, BigDecimal("350").compareTo(byCurrency.getValue("USD"))) {
            "expected 350 but was ${byCurrency["USD"]}"
        }
    }

    @Test
    fun `통화가 섞이면 키가 나뉜다`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        val krwStock = asset(userId, accountId, currentValue = BigDecimal("1000000"), currency = "KRW")
        val usdCrypto = asset(userId, accountId, currentValue = BigDecimal("1000"), currency = "USD")

        val byCurrency = listOf(krwStock, usdCrypto).navByCurrency()

        assertEquals(2, byCurrency.size)
        assertEquals(0, BigDecimal("1000000").compareTo(byCurrency.getValue("KRW"))) {
            "expected 1,000,000 KRW but was ${byCurrency["KRW"]}"
        }
        assertEquals(0, BigDecimal("1000").compareTo(byCurrency.getValue("USD"))) {
            "expected 1,000 USD but was ${byCurrency["USD"]}"
        }
    }

    @Test
    fun `대소문자·공백이 정규화된다`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        // Asset.create()는 Currencies.normalize()를 거치며 이미 trim().uppercase()를
        // 적용해버리므로, 그걸로 만든 자산은 navByCurrency() 자신의 정규화를 시험하지
        // 못한다(무엇을 넣어도 currency는 이미 "USD"). navByCurrency()가 스스로도
        // 정규화하는지 보려면 Asset.reconstruct()로 원시(비정규화) 값을 그대로 심어야 한다 —
        // DB에서 읽어온 레코드를 domain으로 복원할 때 쓰는 경로라 검증을 건너뛴다.
        val lower = rawAsset(userId, accountId, currentValue = BigDecimal("10"), currency = "usd")
        val padded = rawAsset(userId, accountId, currentValue = BigDecimal("20"), currency = " USD ")
        val plain = rawAsset(userId, accountId, currentValue = BigDecimal("30"), currency = "USD")

        val byCurrency = listOf(lower, padded, plain).navByCurrency()

        assertEquals(1, byCurrency.size)
        assertTrue(byCurrency.containsKey("USD")) { "expected normalized key USD but keys were ${byCurrency.keys}" }
        assertEquals(0, BigDecimal("60").compareTo(byCurrency.getValue("USD"))) {
            "expected 60 but was ${byCurrency["USD"]}"
        }
    }

    @Test
    fun `빈 목록은 빈 맵`() {
        val byCurrency = emptyList<Asset>().navByCurrency()

        assertTrue(byCurrency.isEmpty()) { "expected empty map but was $byCurrency" }
    }

    @Test
    fun `navInKrw와 근사한다`() {
        val userId = UUID.randomUUID()
        val accountId = UUID.randomUUID()

        val assets = listOf(
            asset(userId, accountId, currentValue = BigDecimal("1000000"), currency = "KRW"),
            asset(userId, accountId, currentValue = BigDecimal("1000.33"), currency = "USD"),
            asset(userId, accountId, currentValue = BigDecimal("250.77"), currency = "USD"),
            asset(userId, accountId, currentValue = BigDecimal("42.11"), currency = "USDT"),
        )

        val viaNavInKrw = assets.navInKrw(fx)
        val viaByCurrency = assets.navByCurrency()
            .entries
            .fold(BigDecimal.ZERO) { acc, (currency, value) -> acc + fx.toKrw(value, currency) }

        // navInKrw는 자산 단위로 반올림하고, navByCurrency 경유는 통화 단위로 한 번만 반올림하므로
        // 정확히 같지 않을 수 있다. 허용 오차는 "자산 개수만큼의 KRW"로 잡는다 — 자산마다
        // 반올림 오차가 최대 1원씩 어긋날 수 있다고 가정한 보수적인 상한이다.
        val tolerance = BigDecimal(assets.size)
        val diff = viaNavInKrw.subtract(viaByCurrency).abs()

        assertTrue(diff <= tolerance) {
            "expected diff <= $tolerance but was $diff (navInKrw=$viaNavInKrw, viaByCurrency=$viaByCurrency)"
        }
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

    /**
     * [Asset.create]와 달리 [Asset.reconstruct]는 통화를 정규화하지 않는다(DB에서 읽어온
     * 값을 그대로 복원하는 경로라서). 그 틈을 이용해 비정규화된 currency를 그대로 심는다.
     */
    private fun rawAsset(
        userId: UUID,
        accountId: UUID,
        currentValue: BigDecimal,
        currency: String,
    ): Asset {
        val now = LocalDateTime.now()
        return Asset.reconstruct(
            id = UUID.randomUUID(),
            userId = userId,
            accountId = accountId,
            category = AssetCategory.FINANCIAL,
            type = AssetType.STOCK,
            sourceType = AssetSourceType.STOCK_API,
            name = "test-raw",
            symbol = null,
            quantity = BigDecimal.ONE,
            purchasePrice = currentValue,
            currentValue = currentValue,
            currency = currency,
            valuationMethod = ValuationMethod.BALANCE,
            confidenceLevel = ConfidenceLevel.HIGH,
            lastUpdatedAt = now,
            createdAt = now,
            memo = null,
            liquidityType = AssetLiquidityType.LIQUID,
        )
    }
}
