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
     *
     * **getUsdToKrw()(1400)와 usdQuoteRef().rate(1383.50)를 일부러 다르게 둔다.**
     * 두 값이 같으면 sourceOf가 표시 환율을 어느 쪽에서 읽었는지 어떤 테스트로도 구분할 수 없고,
     * 회차 라벨만 고시에서 가져오고 숫자는 다른 데서 가져오는 변이가 그대로 살아남는다.
     */
    private val quotedFxRates = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun getUsdToKrw(): BigDecimal = BigDecimal("1400")
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
        assertNoDrift(converter)
    }

    // 실제 화면에 렌더되는 건 고시가 있는 경로다. 그런데 위 converter의 스텁은 usdQuoteRef()가
    // 기본값 null이라 폴백 분기만 밟는다 — 정작 프로덕션 경로가 드리프트 검사를 한 번도 안 받는다.
    @Test
    fun `고시 경로에서도 sourceOf의 환율과 toKrw의 환율은 같다`() {
        assertNoDrift(quotedConverter)
    }

    private fun assertNoDrift(target: CurrencyConverter) {
        listOf("USD", "USDT", "BTC", "ETH").forEach { code ->
            val source = target.sourceOf(code)
            assertThat(source).describedAs("sourceOf(%s)", code).isNotNull

            val viaSource = (source!!.rate * BigDecimal("1000")).setScale(0, RoundingMode.HALF_UP)
            assertThat(target.toKrw(BigDecimal("1000"), code))
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
        // 스텁의 getUsdToKrw()는 1400 — 위 1383.50 단언은 고시에서 온 값만 통과시킨다
        assertThat(source.rate).isNotEqualByComparingTo(quotedFxRates.getUsdToKrw())
    }

    // 고시가 있을 때 표시되는 환율은 반드시 그 고시에서 온 값이어야 한다.
    // 회차 라벨은 고시에서 가져오면서 환율만 다른 데서 가져오면, 화면이 Binance 숫자에
    // "8/11 32회차 고시"를 붙이게 된다 — AF-105가 막으려는 실패 그 자체다.
    @Test
    fun `고시가 있으면 환율도 반드시 그 고시에서 온다`() {
        val source = quotedConverter.sourceOf("USD")!!

        assertThat(source.rate).isEqualByComparingTo(BigDecimal("1383.50"))
        assertThat(source.rate).isNotEqualByComparingTo(BigDecimal("1400")) // getUsdToKrw() 값
    }

    // FxSource KDoc의 불변식: 고시가 아닌 소스에 회차를 붙이면 사용자가 대조하러 갔다가
    // 아무것도 못 찾는다. 없는 근거를 있는 척하는 게 근거를 안 주는 것보다 나쁘다.
    @Test
    fun `고시가 아닌 소스에는 기준일도 회차도 붙이지 않는다`() {
        listOf("USDT", "BTC", "ETH").forEach { code ->
            val source = converter.sourceOf(code)!!
            assertThat(source.baseDate).describedAs("%s baseDate", code).isNull()
            assertThat(source.roundNo).describedAs("%s roundNo", code).isNull()
        }
    }

    // 화면에 그대로 나가는 문구는 USD에서만 검증되고 있었다. 라벨이 곧 기능이다 —
    // USDT가 "하나은행 매매기준율"을 참칭해도 스위트는 초록이었다.
    @Test
    fun `고시가 아닌 소스도 자기 출처 문구를 정확히 밝힌다`() {
        assertThat(converter.sourceOf("USDT")!!.source).isEqualTo("거래소 시세")
        assertThat(converter.sourceOf("BTC")!!.source).isEqualTo("코인 시세")
        assertThat(converter.sourceOf("ETH")!!.source).isEqualTo("코인 시세")
    }

    /**
     * 코인 시세가 없을 때 **null로 degrade하면 안 된다.**
     *
     * 코인에는 폴백 상수가 없어 getCryptoToKrw가 예외를 던진다(AF-99).
     * 그 예외를 sourceOf에서 삼켜 null을 돌려주고 싶은 유혹이 있다 — 출처 표기는 화면 장식이고,
     * GetDashboardUseCase가 mapNotNull로 부르니 대시보드 500을 피할 수 있어 보인다.
     *
     * **그러면 안 된다.** toKrw가 sourceOf 위에 서 있고 거기서 null은 "미지원 통화 → 원금 그대로"다.
     * null을 주는 순간 0.5 BTC가 0.5원이 되어 QA P3에서 고쳤던 6천만 배 저평가가 되살아난다.
     * 실제로 이 테스트가 그 회귀를 한 번 잡았다.
     *
     * 대시보드가 500이 나는 편이 조용한 6천만 배 저평가보다 낫다.
     */
    @Test
    fun `코인 시세가 없으면 예외가 전파된다 - null로 떨어뜨리면 0_5 BTC가 0_5원이 된다`() {
        val noCryptoRate = object : FxRateService {
            override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
            override fun setUsdtToKrw(rate: BigDecimal) {}
            override fun getCryptoToKrw(symbol: String): BigDecimal =
                throw IllegalStateException("$symbol KRW 시세가 없습니다")
            override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {}
        }
        val target = CurrencyConverter(noCryptoRate)

        // 표기 경로도 예외를 흘린다 — 여기서 null을 주면 아래 평가가 조용히 틀린다
        org.assertj.core.api.Assertions
            .assertThatThrownBy { target.sourceOf("BTC") }
            .isInstanceOf(IllegalStateException::class.java)

        // 평가: 0.5원을 돌려주느니 죽는다
        org.assertj.core.api.Assertions
            .assertThatThrownBy { target.toKrw(BigDecimal("0.5"), "BTC") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `소문자로 물어도 통화 코드는 대문자로 돌려준다`() {
        listOf("usd", "usdt", "btc", "eth").forEach { code ->
            assertThat(converter.sourceOf(code)!!.currency)
                .describedAs("sourceOf(%s)", code)
                .isEqualTo(code.uppercase())
        }
    }

    // 반올림·소수 자릿수가 어떤 테스트로도 안 잡혀 있었다. setScale(-3)(천원 단위 반올림)까지
    // 통과하던 상태라, 계약(@return 소수점 0자리, HALF_UP)을 실제로 고정한다.
    @Test
    fun `환산 결과는 소수점 없는 원 단위이고 절반은 올린다`() {
        // fxRates 스텁의 USDT = 1400. 1.0025 × 1400 = 1403.5 — 나머지가 정확히 0.5라
        // HALF_UP(1404)과 DOWN(1403)이 갈리고, setScale(-3)이면 1000이 되어 또 갈린다.
        val result = converter.toKrw(BigDecimal("1.0025"), "USDT")

        assertThat(result).isEqualByComparingTo(BigDecimal("1404"))
        assertThat(result.scale()).isZero()
    }
}
