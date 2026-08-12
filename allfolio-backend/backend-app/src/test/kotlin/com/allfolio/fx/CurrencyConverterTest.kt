package com.allfolio.fx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CurrencyConverterTest {

    private val fxRates = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
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
    fun `usdt converts with cached rate`() {
        assertEquals(0, BigDecimal("140000").compareTo(converter.toKrw(BigDecimal("100"), "USDT")))
    }

    @Test
    fun `usd falls back to the usdt rate when no official quote exists`() {
        // getUsdToKrw()의 default가 getUsdtToKrw()다 — 하나은행 수집 전에는 현행 동작 그대로.
        // 1:1 폴백은 달러 자산을 1400배 축소하던 버그라 여기로 떨어지면 안 된다.
        assertEquals(0, BigDecimal("140000").compareTo(converter.toKrw(BigDecimal("100"), "USD")))
    }

    @Test
    fun `usd and usdt use different rates once an official quote exists`() {
        // AF-99 분리의 핵심. 공식 고시(1380)와 거래소 시세(1400)가 갈리면 USD는 고시로,
        // USDT는 거래소 시세로 가야 한다. 한쪽으로 접히면 이 테스트가 잡는다.
        val split = object : FxRateService by fxRates {
            override fun getUsdToKrw(): BigDecimal = BigDecimal("1380")
        }
        val converter = CurrencyConverter(split)

        assertEquals(0, BigDecimal("138000").compareTo(converter.toKrw(BigDecimal("100"), "USD")))
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
