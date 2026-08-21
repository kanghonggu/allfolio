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

    /** HANARO K-반도체 — 주식시세정보에서 0건이 나온 그 ETF */
    private val etf = "395270"

    /** 6자리 코드를 `.KS`/`.KQ` 중 어느 형태로 묻든 같은 종목으로 답한다 — 티커 표기는 구현 사정이다 */
    private class FakeYahoo(private val prices: Map<String, BigDecimal>) : YahooFinanceClient() {
        val asked = mutableListOf<String>()
        override fun getPrice(symbol: String): BigDecimal? {
            asked += symbol
            return prices.entries.firstOrNull { symbol.startsWith(it.key) }?.value
        }
    }

    /**
     * 주식시세정보와 증권상품시세정보는 **다른 서비스**다 — 한 클라이언트의 두 메서드이지만
     * 서로의 커버리지가 겹치지 않는다(주식 쪽엔 ETF가 없고, 그 반대도 마찬가지다).
     * 그래서 값과 호출 횟수를 따로 센다.
     */
    private class FakeFsc(
        private val price: BigDecimal?,
        private val etfPrice: BigDecimal? = null,
    ) : FscStockClient("", ObjectMapper()) {
        var callCount = 0
        var etfCallCount = 0
        override fun getPrice(symbol: String): BigDecimal? {
            callCount++
            return price
        }
        override fun getEtfPrice(symbol: String): BigDecimal? {
            etfCallCount++
            return etfPrice
        }
    }

    private fun stockAccount() = Account.create(
        userId      = UUID.randomUUID(),
        provider    = AccountProvider.STOCK,
        accountType = AccountType.STOCK,
        accountName = "심심풀이",
        currency    = "KRW",
    )

    private fun buy(
        account: Account,
        symbol: String,
        qty: String,
        price: String,
        name: String = "삼성전자",
    ) = StockTrade.create(
        accountId   = account.id,
        userId      = account.userId,
        tradeType   = StockTradeType.BUY,
        stockName   = name,
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

    // ── ETF: 폴백의 세 번째 단계 ───────────────────────────────────────

    /**
     * ETF는 주식시세정보에 **없다** — 2026-08-21 실측: `likeSrtnCd=395270`(HANARO K-반도체)로
     * 물으면 `totalCount=0`이다. 그래서 Yahoo가 막히면 ETF만 폴백 없이 평균 매입단가로
     * 떨어졌고, 화면의 수익률이 영구히 0%였다. 증권상품시세정보가 그 자리를 받는다.
     */
    @Test
    fun `Yahoo와 FSC 주식시세가 모두 없으면 ETF 시세로 떨어진다`() {
        val account = stockAccount()
        val yahoo = FakeYahoo(emptyMap())
        val fsc   = FakeFsc(price = null, etfPrice = BigDecimal("18450"))
        val adapter = StockSyncAdapter(
            FakeStockTradeRepository(listOf(buy(account, etf, "10", "17000", name = "HANARO K-반도체"))),
            yahoo, fsc,
        )

        val asset = adapter.sync(account).single()

        assertEquals(0, BigDecimal("184500").compareTo(asset.currentValue))
        assertEquals(ValuationMethod.MARKET_PRICE, asset.valuationMethod)
    }

    /** 순서는 여전히 Yahoo → 주식시세 → ETF다. ETF는 앞의 둘이 비었을 때만 부른다 */
    @Test
    fun `FSC 주식시세가 값을 주면 ETF는 부르지 않는다`() {
        val account = stockAccount()
        val yahoo = FakeYahoo(emptyMap())
        val fsc   = FakeFsc(price = BigDecimal("247500"), etfPrice = BigDecimal("18450"))
        val adapter = StockSyncAdapter(
            FakeStockTradeRepository(listOf(buy(account, samsung, "10", "255000"))),
            yahoo, fsc,
        )

        val asset = adapter.sync(account).single()

        assertEquals(0, BigDecimal("2475000").compareTo(asset.currentValue))
        assertEquals(0, fsc.etfCallCount, "일반 주식에 ETF 오퍼레이션을 물을 이유가 없다")
    }

    /** FSC 계열은 국내 6자리 코드 전용이다 — 해외 티커로 두 번 헛물켜지 않는다 */
    @Test
    fun `해외 티커는 FSC를 아예 부르지 않는다`() {
        val account = stockAccount()
        val yahoo = FakeYahoo(emptyMap())
        val fsc   = FakeFsc(price = BigDecimal("999"), etfPrice = BigDecimal("999"))
        val adapter = StockSyncAdapter(
            FakeStockTradeRepository(listOf(buy(account, "AAPL", "10", "200", name = "Apple"))),
            yahoo, fsc,
        )

        val asset = adapter.sync(account).single()

        assertEquals(0, fsc.callCount)
        assertEquals(0, fsc.etfCallCount)
        assertEquals(ValuationMethod.USER_INPUT, asset.valuationMethod)
    }
}
