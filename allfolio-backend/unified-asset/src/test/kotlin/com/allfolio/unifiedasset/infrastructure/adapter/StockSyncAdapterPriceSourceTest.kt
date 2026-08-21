package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.usecase.FakeStockTradeRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 국내 종목 현재가의 **조회 순서**를 고정한다.
 *
 * FSC(공공데이터포털)는 당일 종가를 주지 않는다 — 제공자 문서가 "기준일자로부터 영업일 하루 뒤"라고
 * 명시한다. 그래서 FSC를 먼저 물으면 장이 끝나도 화면은 전일 종가에 머문다(2026-08-20 실측:
 * 삼성전자 8/19 종가 247,500이 하루 종일 "현재가"였다). Yahoo는 같은 시각에 당일 값을 준다.
 *
 * **순서가 곧 신선도다.** 이 테스트가 지키는 건 값이 아니라 그 순서다.
 */
class StockSyncAdapterPriceSourceTest {

    private val samsung = "005930"

    /** 6자리 코드를 `.KS`/`.KQ` 중 어느 형태로 묻든 같은 종목으로 답한다 — 티커 표기는 구현 사정이다 */
    private class FakeYahoo(private val prices: Map<String, BigDecimal>) : YahooFinanceClient() {
        val asked = mutableListOf<String>()
        override fun getPrice(symbol: String): BigDecimal? {
            asked += symbol
            return prices.entries.firstOrNull { symbol.startsWith(it.key) }?.value
        }
    }

    private class FakeFsc(private val price: BigDecimal?) : FscStockClient("", ObjectMapper()) {
        var callCount = 0
        override fun getPrice(symbol: String): BigDecimal? {
            callCount++
            return price
        }
    }

    private fun stockAccount() = Account.create(
        userId      = UUID.randomUUID(),
        provider    = AccountProvider.STOCK,
        accountType = AccountType.STOCK,
        accountName = "심심풀이",
        currency    = "KRW",
    )

    private fun buy(account: Account, symbol: String, qty: String, price: String) = StockTrade.create(
        accountId   = account.id,
        userId      = account.userId,
        tradeType   = StockTradeType.BUY,
        stockName   = "삼성전자",
        symbol      = symbol,
        quantity    = BigDecimal(qty),
        price       = BigDecimal(price),
        totalAmount = BigDecimal(qty).multiply(BigDecimal(price)),
        tradedAt    = LocalDate.now().minusDays(1),
        memo        = null,
    )

    @Test
    fun `국내 6자리 종목은 Yahoo 값을 쓰고 FSC는 부르지 않는다`() {
        val account = stockAccount()
        val yahoo = FakeYahoo(mapOf(samsung to BigDecimal("250000")))   // 오늘 값
        val fsc   = FakeFsc(BigDecimal("247500"))                       // 어제 종가
        val adapter = StockSyncAdapter(
            FakeStockTradeRepository(listOf(buy(account, samsung, "10", "255000"))),
            yahoo, fsc,
        )

        val asset = adapter.sync(account).single()

        assertEquals(0, BigDecimal("2500000").compareTo(asset.currentValue))
        assertEquals(0, fsc.callCount, "Yahoo가 값을 준 종목에 FSC를 부를 이유가 없다")
        assertEquals(ValuationMethod.MARKET_PRICE, asset.valuationMethod)
    }

    @Test
    fun `Yahoo가 값을 못 주면 FSC 종가로 떨어진다`() {
        val account = stockAccount()
        val yahoo = FakeYahoo(emptyMap())
        val fsc   = FakeFsc(BigDecimal("247500"))
        val adapter = StockSyncAdapter(
            FakeStockTradeRepository(listOf(buy(account, samsung, "10", "255000"))),
            yahoo, fsc,
        )

        val asset = adapter.sync(account).single()

        assertTrue(yahoo.asked.isNotEmpty(), "폴백이려면 Yahoo를 먼저 물어봤어야 한다")
        assertEquals(0, BigDecimal("2475000").compareTo(asset.currentValue))
        assertEquals(ValuationMethod.MARKET_PRICE, asset.valuationMethod)
    }
}
