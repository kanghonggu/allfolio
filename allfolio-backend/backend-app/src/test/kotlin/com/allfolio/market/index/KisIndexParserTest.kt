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
    // 보합 픽스처의 크기가 0이면 방향값이 1이든 0이든 결과가 같아 분기가 고정되지 않는다.
    // Task 6의 자기모순 가드도 이걸 못 잡는다 — sign=3인데 vrss·ctrt가 서로 일관되면
    // 가드를 그대로 통과해 보합일이 +3.68%로 저장된다. 방향은 sign에서만 온다는 게 이 파서의
    // 계약이므로, 크기가 0이 아니어도 sign이 보합이면 변동은 0이어야 한다.
    @Test
    fun `부호가 보합이면 값이 0이 아니어도 변동은 0이다`() {
        val q = parser.parse("KOSPI", realResponse(vrss = "233.51", sign = "3", ctrt = "3.68"))

        assertThat(q.change).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(q.changeRate).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(q.prevClose).isEqualByComparingTo(q.price)
    }

    // text()의 trim()이 사라져도 아무 테스트가 안 깨지던 자리.
    // KIS가 값을 패딩해서 주는지는 관측된 바 없지만, 준다면 파싱이 통째로 실패한다.
    @Test
    fun `값에 공백이 섞여 와도 파싱한다`() {
        val q = parser.parse("KOSPI", realResponse(prpr = " 6579.04 ", vrss = " 233.51 ", sign = " 2 "))

        assertThat(q.price).isEqualByComparingTo("6579.04")
        assertThat(q.change).isEqualByComparingTo("233.51")
    }

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
