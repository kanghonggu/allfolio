package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 어댑터의 rateOf가 toKrw가 실제로 쓴 환율과 같은지 못 박는다.
 *
 * AF-105가 CurrencyConverter 층에서 같은 성격의 테스트(sourceOf의 환율 == toKrw의 환율)를
 * 갖고 있다. 여기서는 **포트 어댑터 층**에서 확인한다 — AF-106의 합계 불변식
 * `Σ value_native × fx_rate ≈ nav`가 이 일치에 통째로 기대고 있다.
 */
class FxConverterRateOfTest {

    // USD와 USDT에 **다른** 값을 준다 — 둘이 같으면 canonical 접기 버그를 못 잡는다
    private val usdRate = BigDecimal("1400.5")
    private val usdtRate = BigDecimal("1385.0")

    /**
     * `getUsdToKrw()`를 직접 오버라이드하지 않고 고시([usdQuoteRef])로 준다.
     * 운영에서 USD가 실제로 밟는 경로가 고시 쪽이라, 여기서 폴백만 세워 두면
     * 정작 프로덕션 경로가 rateOf 검사를 한 번도 안 받는다.
     */
    private fun fakeFxRateService() = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = usdtRate
        override fun setUsdtToKrw(rate: BigDecimal) {}
        override fun getCryptoToKrw(symbol: String): BigDecimal =
            throw IllegalStateException("$symbol KRW 시세가 없습니다")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {}
        override fun usdQuoteRef(): UsdQuoteRef =
            UsdQuoteRef(usdRate, java.time.LocalDate.of(2026, 8, 11), 32)
    }

    private fun adapter() = UnifiedAssetFxConverterAdapter(
        CurrencyConverter(fakeFxRateService()),
        mock(HistoricalFxRateJpaRepository::class.java),   // rateOf는 과거 환율을 안 본다
    )

    @Test
    fun `rateOf가 toKrw가 쓴 환율과 같다`() {
        val fx = adapter()
        val amount = BigDecimal("137")
        assertEquals(
            (amount * fx.rateOf("USD")).setScale(0, RoundingMode.HALF_UP),
            fx.toKrw(amount, "USD"),
        )
    }

    @Test
    fun `USDT가 USD로 접히지 않는다`() {
        val fx = adapter()
        assertNotEquals(fx.rateOf("USD"), fx.rateOf("USDT"))
        assertEquals(0, usdtRate.compareTo(fx.rateOf("USDT")))
    }

    @Test
    fun `KRW는 1이다`() {
        assertEquals(0, BigDecimal.ONE.compareTo(adapter().rateOf("KRW")))
    }

    @Test
    fun `미지원 통화도 예외 없이 1이다`() {
        val fx = adapter()
        assertEquals(0, BigDecimal.ONE.compareTo(fx.rateOf("JPY")))
        assertEquals(BigDecimal("500"), fx.toKrw(BigDecimal("500"), "JPY"))
    }

    @Test
    fun `공백과 소문자를 정규화한다`() {
        val fx = adapter()
        assertEquals(fx.rateOf("USD"), fx.rateOf(" usd "))
    }
}
