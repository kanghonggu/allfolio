package com.allfolio.fx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

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

    /**
     * 고시가 있는 경우를 위한 별도 스텁. 위 [fxRates]를 고치지 않는 이유는,
     * 고시 없음 폴백 테스트가 그 스텁의 1390/1400 분리에 의존하기 때문이다.
     */
    private val quotedFxRates = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun getUsdToKrw(): BigDecimal = BigDecimal("1383.50")
        override fun setUsdtToKrw(rate: BigDecimal) {}
        override fun getCryptoToKrw(symbol: String): BigDecimal = when (symbol.uppercase()) {
            "BTC" -> BigDecimal("90000000")
            "ETH" -> BigDecimal("4500000")
            else  -> throw IllegalArgumentException(symbol)
        }
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {}
        override fun usdQuoteRef(): UsdQuoteRef =
            UsdQuoteRef(BigDecimal("1383.50"), LocalDate.of(2026, 8, 11), 32)
    }
    private val quotedConverter = CurrencyConverter(quotedFxRates)

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

    // 이 테스트가 이 파일에서 가장 중요하다.
    // 화면이 밝히는 환율이 실제 환산에 쓰인 환율과 같다는 것 — 그게 AF-105의 전제 전부다.
    // 두 값이 갈라지면 신뢰를 만들려던 표기가 정확히 반대로 동작한다.
    @Test
    fun `sourceOf가 밝히는 환율은 toKrw가 실제로 쓴 환율과 같다`() {
        listOf("USD", "USDT", "BTC", "ETH").forEach { code ->
            val source = converter.sourceOf(code)
            assertThat(source).describedAs("sourceOf(%s)", code).isNotNull

            val viaSource = (source!!.rate * BigDecimal("1000")).setScale(0, RoundingMode.HALF_UP)
            assertThat(converter.toKrw(BigDecimal("1000"), code))
                .describedAs("환산 결과가 %s의 표기 환율과 다르다", code)
                .isEqualByComparingTo(viaSource)
        }
    }

    @Test
    fun `KRW는 환산이 없으므로 출처도 없다`() {
        assertThat(converter.sourceOf("KRW")).isNull()
        assertThat(converter.toKrw(BigDecimal("1000"), "KRW")).isEqualByComparingTo(BigDecimal("1000"))
    }

    @Test
    fun `미지원 통화는 환산도 출처도 없다`() {
        assertThat(converter.sourceOf("JPY")).isNull()
        assertThat(converter.toKrw(BigDecimal("1000"), "JPY")).isEqualByComparingTo(BigDecimal("1000"))
    }

    // 기존 스텁(fxRates)은 usdQuoteRef()를 오버라이드하지 않아 기본값 null이고,
    // getUsdToKrw()=1390 / getUsdtToKrw()=1400으로 갈라 둔다. 그 스텁을 그대로 쓰면
    // 폴백이 getUsdToKrw()를 존중하는지가 바로 드러난다 — 1400이 나오면 계약을 우회한 것이다.
    @Test
    fun `고시가 없으면 근사임을 밝히되 표기를 없애지 않는다`() {
        val source = converter.sourceOf("USD")

        assertThat(source).isNotNull
        assertThat(source!!.rate).isEqualByComparingTo(BigDecimal("1390"))
        assertThat(source.source).contains("고시 없음")
        assertThat(source.baseDate).isNull()
        assertThat(source.roundNo).isNull()
    }

    @Test
    fun `고시가 있으면 USD 출처에 기준일과 회차가 실린다`() {
        val source = quotedConverter.sourceOf("usd")   // 소문자도 받아야 한다

        assertThat(source!!.currency).isEqualTo("USD")
        assertThat(source.rate).isEqualByComparingTo(BigDecimal("1383.50"))
        assertThat(source.baseDate).isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(source.roundNo).isEqualTo(32)
        assertThat(source.source).isEqualTo("하나은행 매매기준율")
    }
}
