package com.allfolio.unifiedasset.domain.asset

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

class AssetTest {

    private val userId    = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    // ── totalPurchaseCost ─────────────────────────────────────

    @Test
    fun `LIQUID 자산 - 매입가는 수량 × 단가`() {
        val asset = stock(quantity = bd("10"), purchasePrice = bd("50000"))
        assertEquals(bd("500000"), asset.totalPurchaseCost())
    }

    @Test
    fun `ILLIQUID 부동산 - 매입가는 총액 그대로 (수량 곱하지 않음)`() {
        val asset = realEstate(quantity = bd("1"), purchasePrice = bd("500000000"))
        assertEquals(bd("500000000"), asset.totalPurchaseCost())
    }

    @Test
    fun `VEHICLE도 ILLIQUID - 매입가는 총액 그대로`() {
        val asset = vehicle(quantity = bd("1"), purchasePrice = bd("30000000"))
        assertEquals(bd("30000000"), asset.totalPurchaseCost())
    }

    // ── unrealizedPnl ─────────────────────────────────────────

    @Test
    fun `평가익 - 현재가치가 매입원가보다 높으면 양수`() {
        val asset = stock(quantity = bd("10"), purchasePrice = bd("50000"), currentValue = bd("600000"))
        assertEquals(bd("100000"), asset.unrealizedPnl())
    }

    @Test
    fun `평가손 - 현재가치가 매입원가보다 낮으면 음수`() {
        val asset = stock(quantity = bd("10"), purchasePrice = bd("50000"), currentValue = bd("400000"))
        assertEquals(bd("-100000"), asset.unrealizedPnl())
    }

    @Test
    fun `평가손익 0 - 매입원가와 현재가치 동일`() {
        val asset = stock(quantity = bd("10"), purchasePrice = bd("50000"), currentValue = bd("500000"))
        assertEquals(BigDecimal.ZERO, asset.unrealizedPnl())
    }

    // ── returnRate ────────────────────────────────────────────

    @Test
    fun `수익률 - 20% 수익`() {
        val asset = stock(quantity = bd("10"), purchasePrice = bd("50000"), currentValue = bd("600000"))
        assertEquals(0, bd("20").compareTo(asset.returnRate().setScale(2)))
    }

    @Test
    fun `수익률 - 매입원가 0이면 0 반환`() {
        val asset = stock(quantity = bd("0"), purchasePrice = bd("0"), currentValue = bd("0"))
        assertEquals(BigDecimal.ZERO, asset.returnRate())
    }

    // ── netEquity ─────────────────────────────────────────────

    @Test
    fun `순자산 - 대출 없으면 현재가치 그대로`() {
        val asset = realEstate(currentValue = bd("500000000"), loanAmount = null)
        assertEquals(bd("500000000"), asset.netEquity())
    }

    @Test
    fun `순자산 - 대출 차감`() {
        val asset = realEstate(currentValue = bd("500000000"), loanAmount = bd("200000000"))
        assertEquals(bd("300000000"), asset.netEquity())
    }

    // ── create 유효성 검사 ─────────────────────────────────────

    @Test
    fun `이름이 빈 문자열이면 생성 실패`() {
        assertThrows<IllegalArgumentException> {
            Asset.create(
                userId = userId, accountId = accountId,
                category = AssetCategory.FINANCIAL, type = AssetType.STOCK,
                sourceType = AssetSourceType.MANUAL, name = "   ",
                symbol = null, quantity = bd("1"), purchasePrice = bd("1000"),
                currentValue = bd("1000"), currency = "KRW",
                valuationMethod = ValuationMethod.USER_INPUT,
            )
        }
    }

    @Test
    fun `수량이 음수이면 생성 실패`() {
        assertThrows<IllegalArgumentException> {
            Asset.create(
                userId = userId, accountId = accountId,
                category = AssetCategory.FINANCIAL, type = AssetType.STOCK,
                sourceType = AssetSourceType.MANUAL, name = "삼성전자",
                symbol = null, quantity = bd("-1"), purchasePrice = bd("1000"),
                currentValue = bd("1000"), currency = "KRW",
                valuationMethod = ValuationMethod.USER_INPUT,
            )
        }
    }

    @Test
    fun `현재가치가 음수이면 생성 실패`() {
        assertThrows<IllegalArgumentException> {
            Asset.create(
                userId = userId, accountId = accountId,
                category = AssetCategory.FINANCIAL, type = AssetType.STOCK,
                sourceType = AssetSourceType.MANUAL, name = "삼성전자",
                symbol = null, quantity = bd("1"), purchasePrice = bd("1000"),
                currentValue = bd("-1"), currency = "KRW",
                valuationMethod = ValuationMethod.USER_INPUT,
            )
        }
    }

    // ── confidenceLevel 자동 설정 ──────────────────────────────

    @Test
    fun `MARKET_PRICE valuation - confidenceLevel HIGH`() {
        val asset = stock(valuationMethod = ValuationMethod.MARKET_PRICE)
        assertEquals(ConfidenceLevel.HIGH, asset.confidenceLevel)
    }

    @Test
    fun `BALANCE valuation - confidenceLevel HIGH`() {
        val asset = stock(valuationMethod = ValuationMethod.BALANCE)
        assertEquals(ConfidenceLevel.HIGH, asset.confidenceLevel)
    }

    @Test
    fun `USER_INPUT valuation - confidenceLevel LOW`() {
        val asset = stock(valuationMethod = ValuationMethod.USER_INPUT)
        assertEquals(ConfidenceLevel.LOW, asset.confidenceLevel)
    }

    // ── symbol 대소문자 ───────────────────────────────────────

    @Test
    fun `CRYPTO 심볼은 항상 대문자로 저장`() {
        val asset = Asset.create(
            userId = userId, accountId = accountId,
            category = AssetCategory.FINANCIAL, type = AssetType.CRYPTO,
            sourceType = AssetSourceType.MANUAL, name = "비트코인",
            symbol = "btc", quantity = bd("1"), purchasePrice = bd("50000"),
            currentValue = bd("50000"), currency = "USD",
            valuationMethod = ValuationMethod.MARKET_PRICE,
        )
        assertEquals("BTC", asset.symbol)
    }

    @Test
    fun `STOCK 심볼은 항상 대문자로 저장`() {
        val asset = stock(symbol = "aapl")
        assertEquals("AAPL", asset.symbol)
    }

    @Test
    fun `부동산 심볼은 대소문자 유지`() {
        val asset = realEstate(symbol = "강남아파트-101")
        assertEquals("강남아파트-101", asset.symbol)
    }

    // ── liquidityType 자동 설정 ───────────────────────────────

    @Test
    fun `REAL_ESTATE는 ILLIQUID`() {
        val asset = realEstate()
        assertEquals(AssetLiquidityType.ILLIQUID, asset.liquidityType)
    }

    @Test
    fun `VEHICLE은 ILLIQUID`() {
        val asset = vehicle()
        assertEquals(AssetLiquidityType.ILLIQUID, asset.liquidityType)
    }

    @Test
    fun `STOCK은 LIQUID`() {
        val asset = stock()
        assertEquals(AssetLiquidityType.LIQUID, asset.liquidityType)
    }

    @Test
    fun `CRYPTO는 LIQUID`() {
        val asset = Asset.create(
            userId = userId, accountId = accountId,
            category = AssetCategory.FINANCIAL, type = AssetType.CRYPTO,
            sourceType = AssetSourceType.MANUAL, name = "비트코인",
            symbol = "BTC", quantity = bd("1"), purchasePrice = bd("50000"),
            currentValue = bd("50000"), currency = "USD",
            valuationMethod = ValuationMethod.MARKET_PRICE,
        )
        assertEquals(AssetLiquidityType.LIQUID, asset.liquidityType)
    }

    // ── subType 대문자 저장 ───────────────────────────────────

    @Test
    fun `subType은 대문자로 정규화`() {
        val asset = realEstate(subType = "own")
        assertEquals("OWN", asset.subType)
    }

    // ── JEONSE도 ILLIQUID ────────────────────────────────────

    @Test
    fun `JEONSE는 ILLIQUID`() {
        val asset = Asset.create(
            userId = userId, accountId = accountId,
            category = AssetCategory.MANUAL, type = AssetType.JEONSE,
            sourceType = AssetSourceType.MANUAL, name = "전세보증금",
            symbol = null, quantity = bd("1"), purchasePrice = bd("300000000"),
            currentValue = bd("300000000"), currency = "KRW",
            valuationMethod = ValuationMethod.USER_INPUT,
        )
        assertEquals(AssetLiquidityType.ILLIQUID, asset.liquidityType)
    }

    // ── helper factories ──────────────────────────────────────

    private fun stock(
        symbol: String? = "AAPL",
        quantity: BigDecimal = bd("1"),
        purchasePrice: BigDecimal = bd("1000"),
        currentValue: BigDecimal = bd("1000"),
        valuationMethod: ValuationMethod = ValuationMethod.MARKET_PRICE,
    ) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK,
        sourceType = AssetSourceType.MANUAL, name = "테스트 주식",
        symbol = symbol, quantity = quantity, purchasePrice = purchasePrice,
        currentValue = currentValue, currency = "KRW",
        valuationMethod = valuationMethod,
    )

    private fun realEstate(
        quantity: BigDecimal = bd("1"),
        purchasePrice: BigDecimal = bd("500000000"),
        currentValue: BigDecimal = bd("500000000"),
        loanAmount: BigDecimal? = null,
        symbol: String? = null,
        subType: String? = null,
    ) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.MANUAL, type = AssetType.REAL_ESTATE,
        sourceType = AssetSourceType.MANUAL, name = "아파트",
        symbol = symbol, quantity = quantity, purchasePrice = purchasePrice,
        currentValue = currentValue, currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
        loanAmount = loanAmount, subType = subType,
    )

    private fun vehicle(
        quantity: BigDecimal = bd("1"),
        purchasePrice: BigDecimal = bd("30000000"),
        currentValue: BigDecimal = bd("25000000"),
    ) = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.MANUAL, type = AssetType.VEHICLE,
        sourceType = AssetSourceType.MANUAL, name = "내 차",
        symbol = null, quantity = quantity, purchasePrice = purchasePrice,
        currentValue = currentValue, currency = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
    )

    private fun bd(s: String) = BigDecimal(s)
}
