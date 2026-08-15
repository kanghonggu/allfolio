package com.allfolio.snapshot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class NavCurrencyAggregationTest {

    private fun bd(v: String) = BigDecimal(v)
    private val usd = UUID.randomUUID()
    private val usd2 = UUID.randomUUID()
    private val krw = UUID.randomUUID()
    private val jpy = UUID.randomUUID()

    private val rates: (String) -> BigDecimal = { code ->
        when (code) {
            "USD" -> bd("1400")
            "KRW" -> BigDecimal.ONE
            else -> BigDecimal.ONE     // 미지원 통화 — CurrencyConverter가 원금을 그대로 돌려준다
        }
    }

    @Test
    fun `같은 통화 자산은 하나로 합쳐진다`() {
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(usd to bd("10"), usd2 to bd("5")),
            prices = mapOf(usd to NativePrice(bd("200"), "USD"), usd2 to NativePrice(bd("100"), "USD")),
            rateOf = rates,
        )
        assertEquals(1, result.size)
        assertEquals("USD", result[0].currency)
        assertEquals(0, bd("2500").compareTo(result[0].valueNative))   // 10*200 + 5*100
        assertEquals(0, bd("1400").compareTo(result[0].fxRate))
    }

    @Test
    fun `미지원 통화도 예외 없이 환율 1로 기록된다`() {
        // JPY는 CurrencyConverter가 환산하지 않는다 — 예외를 던지면 스냅샷이 깨진다
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(jpy to bd("3")),
            prices = mapOf(jpy to NativePrice(bd("1000"), "JPY")),
            rateOf = rates,
        )
        assertEquals(1, result.size)
        assertEquals("JPY", result[0].currency)
        assertEquals(0, BigDecimal.ONE.compareTo(result[0].fxRate))
    }

    @Test
    fun `합계가 원화 평가액과 일치한다`() {
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(usd to bd("10"), krw to bd("2")),
            prices = mapOf(usd to NativePrice(bd("200"), "USD"), krw to NativePrice(bd("50000"), "KRW")),
            rateOf = rates,
        )
        val totalKrw = result.fold(BigDecimal.ZERO) { acc, v -> acc + v.valueNative * v.fxRate }
        assertEquals(0, bd("2900000").compareTo(totalKrw))   // 10*200*1400 + 2*50000
    }

    @Test
    fun `시세가 없는 자산은 건너뛴다`() {
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(usd to bd("10"), usd2 to bd("99")),
            prices = mapOf(usd to NativePrice(bd("200"), "USD")),
            rateOf = rates,
        )
        assertEquals(0, bd("2000").compareTo(result.single().valueNative))
    }

    @Test
    fun `통화 코드는 대문자로 정규화된다`() {
        val result = NavCurrencyDailyStore.aggregate(
            quantities = mapOf(usd to bd("1"), usd2 to bd("1")),
            prices = mapOf(usd to NativePrice(bd("100"), "usd"), usd2 to NativePrice(bd("100"), "USD")),
            rateOf = rates,
        )
        assertEquals(1, result.size)
        assertEquals(0, bd("200").compareTo(result[0].valueNative))
    }

    @Test
    fun `자산이 하나도 없으면 빈 목록이다`() {
        // replace()가 빈 batchUpdate를 안 내도록 하는 계약
        val result = NavCurrencyDailyStore.aggregate(emptyMap(), emptyMap()) { BigDecimal.ONE }
        assertEquals(0, result.size)
    }
}
