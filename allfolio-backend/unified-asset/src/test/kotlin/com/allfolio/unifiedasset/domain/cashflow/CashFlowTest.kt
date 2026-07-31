package com.allfolio.unifiedasset.domain.cashflow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class CashFlowTest {
    private val user = UUID.randomUUID()
    private val a1 = UUID.randomUUID()
    private val a2 = UUID.randomUUID()
    private val date = LocalDate.of(2026, 6, 10)

    @Test
    fun `FlowType 분류 헬퍼`() {
        assertThat(FlowType.TRANSFER_IN.isInternal()).isTrue
        assertThat(FlowType.FX_OUT.isInternal()).isTrue
        assertThat(FlowType.DEPOSIT.isInternal()).isFalse
        assertThat(FlowType.WITHDRAWAL.isInternal()).isFalse
        assertThat(FlowType.DEPOSIT.isInflow()).isTrue
        assertThat(FlowType.TRANSFER_IN.isInflow()).isTrue
        assertThat(FlowType.WITHDRAWAL.isOutflow()).isTrue
        assertThat(FlowType.FX_OUT.isOutflow()).isTrue
    }

    @Test
    fun `signedKrw는 외부흐름만 부호를 갖고 내부는 0`() {
        fun cf(t: FlowType) = CashFlow.create(user, a1, date, t, BigDecimal.TEN, "KRW", BigDecimal("1000"), null)
        assertThat(cf(FlowType.DEPOSIT).signedKrw()).isEqualByComparingTo("1000")
        assertThat(cf(FlowType.WITHDRAWAL).signedKrw()).isEqualByComparingTo("-1000")
        assertThat(cf(FlowType.TRANSFER_IN).signedKrw()).isEqualByComparingTo("0")
        assertThat(cf(FlowType.TRANSFER_OUT).signedKrw()).isEqualByComparingTo("0")
        assertThat(cf(FlowType.FX_IN).signedKrw()).isEqualByComparingTo("0")
        assertThat(cf(FlowType.FX_OUT).signedKrw()).isEqualByComparingTo("0")
    }

    @Test
    fun `transferPair는 동일 linkId로 OUT@from IN@to 2레그를 만든다`() {
        val (out, inn) = CashFlow.transferPair(user, a1, a2, date, BigDecimal("500"), "KRW", BigDecimal("500"), "이체")
        assertThat(out.type).isEqualTo(FlowType.TRANSFER_OUT)
        assertThat(out.accountId).isEqualTo(a1)
        assertThat(inn.type).isEqualTo(FlowType.TRANSFER_IN)
        assertThat(inn.accountId).isEqualTo(a2)
        assertThat(out.linkId).isNotNull
        assertThat(out.linkId).isEqualTo(inn.linkId)
        assertThat(out.amount).isEqualByComparingTo("500")
        assertThat(inn.amount).isEqualByComparingTo("500")
    }

    @Test
    fun `transferPair 같은 계좌면 예외`() {
        assertThatThrownBy { CashFlow.transferPair(user, a1, a1, date, BigDecimal.TEN, "KRW", BigDecimal.TEN, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `fxPair는 동일 linkId로 FX_OUT fromCcy FX_IN toCcy 2레그를 만든다`() {
        val (out, inn) = CashFlow.fxPair(user, a1, date,
            BigDecimal("1300000"), "KRW", BigDecimal("1300000"),
            BigDecimal("1000"), "USD", BigDecimal("1300000"), "환전")
        assertThat(out.type).isEqualTo(FlowType.FX_OUT)
        assertThat(out.currency).isEqualTo("KRW")
        assertThat(inn.type).isEqualTo(FlowType.FX_IN)
        assertThat(inn.currency).isEqualTo("USD")
        assertThat(out.linkId).isEqualTo(inn.linkId)
        assertThat(inn.amount).isEqualByComparingTo("1000")
    }

    @Test
    fun `fxPair 같은 통화면 예외`() {
        assertThatThrownBy {
            CashFlow.fxPair(user, a1, date, BigDecimal.TEN, "KRW", BigDecimal.TEN, BigDecimal.TEN, "krw", BigDecimal.TEN, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
