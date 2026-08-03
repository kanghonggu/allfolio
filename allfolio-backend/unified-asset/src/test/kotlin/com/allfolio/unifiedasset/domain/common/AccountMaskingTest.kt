package com.allfolio.unifiedasset.domain.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// QA P2 — 계좌번호 마스킹: 목록/카드에서 원문 계좌번호 노출 금지
class AccountMaskingTest {

    @Test
    fun `계좌번호형 externalId는 앞 4자리만 남기고 마스킹한다`() {
        assertThat(maskAccountNumber("44855393_01")).isEqualTo("4485****_01")
        assertThat(maskAccountNumber("1234567890")).isEqualTo("1234******")
    }

    @Test
    fun `계좌번호 여부 판별 - 숫자 6자리 이상`() {
        assertThat(isAccountNumberLike("44855393_01")).isTrue()
        assertThat(isAccountNumberLike("삼성증권")).isFalse()
        assertThat(isAccountNumberLike("Binance")).isFalse()
        assertThat(isAccountNumberLike(null)).isFalse()
    }
}
