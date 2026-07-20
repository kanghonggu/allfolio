package com.allfolio.fx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CurrencyConverterTest {

    private val fxRates = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun setUsdtToKrw(rate: BigDecimal) {}
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
    fun `usd converts like usdt`() {
        // Binance 등 달러 표시 자산 — USDT≈USD 환율 적용 (1:1 폴백은 1400배 축소 버그)
        assertEquals(0, BigDecimal("140000").compareTo(converter.toKrw(BigDecimal("100"), "USD")))
    }
}
