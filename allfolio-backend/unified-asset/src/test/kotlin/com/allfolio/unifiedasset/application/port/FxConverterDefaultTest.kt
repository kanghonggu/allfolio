package com.allfolio.unifiedasset.application.port

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 포트 default 구현 — 과거 환율을 모르는 구현체(테스트 fake 포함)가 그대로 동작해야 한다.
 * KRW는 환산이 없으므로 추정치가 아니다. 이 구분이 없으면 원화 계좌 메모에까지
 * "환율 추정치"가 붙는다.
 */
class FxConverterDefaultTest {

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount.multiply(BigDecimal("1300"))
    }

    @Test
    fun `default는 toKrw에 위임하고 추정치로 표시한다`() {
        val result = fx.toKrwOn(BigDecimal("100"), "USD", LocalDate.of(2025, 8, 11))

        assertThat(result.amountKrw).isEqualByComparingTo("130000")
        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `KRW는 환산이 없으므로 추정치가 아니다`() {
        val result = fx.toKrwOn(BigDecimal("5000"), "krw", LocalDate.of(2025, 8, 11))

        assertThat(result.amountKrw).isEqualByComparingTo("5000")
        assertThat(result.estimated).isFalse()
    }
}
