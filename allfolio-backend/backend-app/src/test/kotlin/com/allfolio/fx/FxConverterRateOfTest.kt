package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

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
    // 소수점을 일부러 붙인다. 딱 떨어지는 90000000이면 0.5 BTC가 45000000으로 정확히
    // 나뉘어 setScale이 아무 일도 안 하고, 이 테스트가 존재하는 이유인 반올림을 안 지난다.
    // 90000000.7 × 0.5 = 45000000.35 → 45000000이라야 반올림이 실제로 걸린다.
    private val btcRate = BigDecimal("90000000.7")

    /**
     * `getUsdToKrw()`를 직접 오버라이드하지 않고 고시([usdQuoteRef])로 준다.
     * 운영에서 USD가 실제로 밟는 경로가 고시 쪽이라, 여기서 폴백만 세워 두면
     * 정작 프로덕션 경로가 rateOf 검사를 한 번도 안 받는다.
     *
     * [cryptoRate]가 null이면 코인 시세 미보유 상태 — `getCryptoToKrw`가 던진다.
     * 코인에는 폴백 상수가 없어서(AF-99) 그게 운영의 실제 동작이다.
     */
    private fun fakeFxRateService(cryptoRate: BigDecimal? = null) = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = usdtRate
        override fun setUsdtToKrw(rate: BigDecimal) {}
        override fun getCryptoToKrw(symbol: String): BigDecimal =
            cryptoRate ?: throw IllegalStateException("$symbol KRW 시세가 없습니다")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {}
        override fun usdQuoteRef(): UsdQuoteRef =
            UsdQuoteRef(usdRate, LocalDate.of(2026, 8, 11), 32)
    }

    private fun adapter(cryptoRate: BigDecimal? = null) = UnifiedAssetFxConverterAdapter(
        CurrencyConverter(fakeFxRateService(cryptoRate)),
        mock(HistoricalFxRateJpaRepository::class.java),   // rateOf는 과거 환율을 안 본다
    )

    @Test
    fun `rateOf가 toKrw가 쓴 환율과 같다`() {
        val fx = adapter()
        val amount = BigDecimal("137")
        assertEquals(0, usdRate.compareTo(fx.rateOf("USD")))
        assertEquals(
            (amount * fx.rateOf("USD")).setScale(0, RoundingMode.HALF_UP),
            fx.toKrw(amount, "USD"),
        )
    }

    @Test
    fun `USDT가 USD로 접히지 않는다`() {
        val fx = adapter()
        assertNotEquals(0, fx.rateOf("USD").compareTo(fx.rateOf("USDT")))
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
        assertEquals(0, BigDecimal("500").compareTo(fx.toKrw(BigDecimal("500"), "JPY")))
    }

    @Test
    fun `공백과 소문자를 정규화한다`() {
        val fx = adapter()
        assertEquals(fx.rateOf("USD"), fx.rateOf(" usd "))
    }

    /**
     * **코인 시세가 없을 때 rateOf는 예외를 삼키지 않는다.**
     *
     * [CurrencyConverterTest]가 한 층 아래 `CurrencyConverter.sourceOf`에서 같은 것을 못 박고 있지만,
     * 그 테스트는 어댑터를 통과하지 않아 rateOf를 전혀 구속하지 못한다. 실제로 rateOf 구현을
     * `runCatching { ... }.getOrNull() ?: ONE`으로 바꾸면 이 테스트가 생기기 전까지는
     * 전체 스위트가 통과했다 — 변이가 살아 있었다.
     *
     * 삼키면 AF-106이 BTC 행에 `fx_rate = 1`을 기록한다. 실제 환율이 9천만이므로
     * 합계 불변식 `Σ value_native × fx_rate ≈ nav`가 9천만 배로 어긋나는데,
     * 예외도 로그도 없어 화면에는 아무 신호가 안 뜬다.
     *
     * `toKrw`도 함께 단언한다 — 두 경로가 같이 죽어야 어긋남이 안 생긴다.
     */
    @Test
    fun `코인 시세가 없으면 rateOf도 예외를 전파한다 - 삼키면 BTC에 환율 1이 박힌다`() {
        val fx = adapter()   // cryptoRate = null → getCryptoToKrw가 던진다

        assertThatThrownBy { fx.rateOf("BTC") }
            .isInstanceOf(IllegalStateException::class.java)

        // 평가 경로도 같은 상황에서 던진다. 한쪽만 살아남으면 두 경로가 어긋난다
        assertThatThrownBy { fx.toKrw(BigDecimal("0.5"), "BTC") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    /**
     * 코인 시세가 **있을 때**의 일치. 소수 수량(0.5 BTC)으로 확인한다 —
     * `toKrw`가 원 단위로 `setScale(0, HALF_UP)`을 하므로 `toKrw(1, c)`로 환율을 역산하면
     * 이 지점에서 가장 크게 어긋난다. rateOf가 따로 있는 이유가 정확히 이것이다.
     */
    @Test
    fun `코인 시세가 있으면 rateOf가 toKrw가 쓴 환율과 같다`() {
        val fx = adapter(btcRate)
        val amount = BigDecimal("0.5")

        assertEquals(0, btcRate.compareTo(fx.rateOf("BTC")))
        assertEquals(
            (amount * fx.rateOf("BTC")).setScale(0, RoundingMode.HALF_UP),
            fx.toKrw(amount, "BTC"),
        )
    }
}
