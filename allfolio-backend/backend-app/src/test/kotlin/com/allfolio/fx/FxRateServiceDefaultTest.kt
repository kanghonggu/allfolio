package com.allfolio.fx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 하나은행 고시를 모르는 구현체(테스트 fake 포함)는 USDT 환율로 근사한다 — 현행 동작이다.
 */
class FxRateServiceDefaultTest {

    private val service = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun setUsdtToKrw(rate: BigDecimal) {}
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal.ZERO
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) {}
    }

    @Test
    fun `default는 USDT 환율을 그대로 준다`() {
        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1400")
    }
}
