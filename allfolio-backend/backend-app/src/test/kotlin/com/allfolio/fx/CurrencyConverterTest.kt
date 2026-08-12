package com.allfolio.fx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CurrencyConverterTest {

    private val fxRates = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun getUsdToKrw(): BigDecimal = BigDecimal("1390")
        override fun setUsdtToKrw(rate: BigDecimal) {}
        override fun getCryptoToKrw(symbol: String): BigDecimal = when (symbol.uppercase()) {
            "BTC" -> BigDecimal("90000000")
            "ETH" -> BigDecimal("4500000")
            else  -> throw IllegalArgumentException(symbol)
        }
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {}
    }
    private val converter = CurrencyConverter(fxRates)

    @Test
    fun `krw passes through`() {
        assertEquals(0, BigDecimal("5000").compareTo(converter.toKrw(BigDecimal("5000"), "KRW")))
    }

    @Test
    fun `usd는 공식 매매기준율로 환산한다`() {
        // AF-99: USD는 하나은행 고시(1390), USDT는 Binance(1400) — 소스가 다르다
        assertEquals(0, BigDecimal("139000").compareTo(converter.toKrw(BigDecimal("100"), "USD")))
    }

    @Test
    fun `usdt는 거래소 시세를 유지한다`() {
        // 김치 프리미엄은 부정확이 아니라 거래소 보유자에게 실현 가능한 값이다
        assertEquals(0, BigDecimal("140000").compareTo(converter.toKrw(BigDecimal("100"), "USDT")))
    }

    @Test
    fun `btc converts with cached krw price`() {
        // QA P3: 0.5 BTC → 45,000,000원 (기존엔 0.5 KRW로 축소되던 버그)
        assertEquals(0, BigDecimal("45000000").compareTo(converter.toKrw(BigDecimal("0.5"), "BTC")))
    }

    @Test
    fun `eth converts with cached krw price`() {
        assertEquals(0, BigDecimal("9000000").compareTo(converter.toKrw(BigDecimal("2"), "ETH")))
    }
}
