package com.allfolio.market.index

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class KisIndexParserTest {

    private val parser = KisIndexParser()

    /** 2026-08-12 운영 실측 (iscd=0001) */
    private fun realResponse(
        prpr: String = "6579.04",
        vrss: String = "233.51",
        sign: String = "2",
        ctrt: String = "3.68",
    ) = mapOf<String, Any?>(
        "bstp_nmix_prpr" to prpr,
        "bstp_nmix_prdy_vrss" to vrss,
        "prdy_vrss_sign" to sign,
        "bstp_nmix_prdy_ctrt" to ctrt,
    )

    @Test
    fun `실측 응답을 그대로 파싱한다`() {
        val q = parser.parse("KOSPI", realResponse())

        assertThat(q.price).isEqualByComparingTo("6579.04")
        assertThat(q.change).isEqualByComparingTo("233.51")
        assertThat(q.changeRate).isEqualByComparingTo("3.68")
        assertThat(q.prevClose).isEqualByComparingTo("6345.53")
    }

    // KIS는 어떤 필드엔 마이너스를 실어 보낸다(dryy_lwpr_vrss_prpr_rate: "-56.02").
    // 값에 이미 부호가 있든 없든 결과가 같아야 한다 — 그래서 절댓값 + sign 조합을 쓴다.
    @Test
    fun `하락일은 원본에 부호가 있든 없든 같은 결과를 낸다`() {
        val withoutSign = parser.parse("KOSPI", realResponse(vrss = "233.51", sign = "5", ctrt = "3.68"))
        val withSign = parser.parse("KOSPI", realResponse(vrss = "-233.51", sign = "5", ctrt = "-3.68"))

        assertThat(withoutSign.change).isEqualByComparingTo("-233.51")
        assertThat(withoutSign.changeRate).isEqualByComparingTo("-3.68")
        assertThat(withSign.change).isEqualByComparingTo(withoutSign.change)
        assertThat(withSign.changeRate).isEqualByComparingTo(withoutSign.changeRate)
    }

    @Test
    fun `보합은 전일과 같다`() {
        val q = parser.parse("KOSPI", realResponse(vrss = "0", sign = "3", ctrt = "0"))

        assertThat(q.change).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(q.prevClose).isEqualByComparingTo(q.price)
    }

    // 기본값을 "상승"으로 두면 알 수 없는 코드가 왔을 때 하락을 상승으로 저장한다.
    @Test
    fun `모르는 부호 코드는 거부한다`() {
        assertThatThrownBy { parser.parse("KOSPI", realResponse(sign = "9")) }
            .isInstanceOf(KisIndexException::class.java)
            .hasMessageContaining("부호")
    }

    @Test
    fun `필드가 없으면 거부한다`() {
        assertThatThrownBy { parser.parse("KOSPI", emptyMap()) }
            .isInstanceOf(KisIndexException::class.java)
    }

    @Test
    fun `숫자가 아닌 값은 거부한다`() {
        assertThatThrownBy { parser.parse("KOSPI", realResponse(prpr = "-")) }
            .isInstanceOf(KisIndexException::class.java)
    }
}
