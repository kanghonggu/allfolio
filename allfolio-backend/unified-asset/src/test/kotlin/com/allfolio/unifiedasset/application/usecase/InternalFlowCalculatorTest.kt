package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class InternalFlowCalculatorTest {
    private val user = UUID.randomUUID()
    private val a1 = UUID.randomUUID(); private val a2 = UUID.randomUUID()
    private val names = mapOf(a1 to "한투", a2 to "미래에셋")
    private fun d(day: Int) = LocalDate.of(2026, 6, day)

    @Test
    fun `이체 페어는 계좌간이체로 from-to 계좌명과 함께 집계된다`() {
        val (out, inn) = CashFlow.transferPair(user, a1, a2, d(10), BigDecimal("500"), "KRW", BigDecimal("500"), "이체")
        val r = InternalFlowCalculator.build(listOf(out, inn), names).single()
        assertThat(r.kind).isEqualTo("계좌간이체")
        assertThat(r.fromAccount).isEqualTo("한투")
        assertThat(r.toAccount).isEqualTo("미래에셋")
        assertThat(r.amountKrw).isEqualByComparingTo("500")
        assertThat(r.fromCurrency).isNull()
    }

    @Test
    fun `환전 페어는 환전으로 통화-금액과 함께 집계된다`() {
        val (out, inn) = CashFlow.fxPair(user, a1, d(11),
            BigDecimal("1300000"), "KRW", BigDecimal("1300000"),
            BigDecimal("1000"), "USD", BigDecimal("1300000"), "환전")
        val r = InternalFlowCalculator.build(listOf(out, inn), names).single()
        assertThat(r.kind).isEqualTo("환전")
        assertThat(r.fromCurrency).isEqualTo("KRW")
        assertThat(r.toCurrency).isEqualTo("USD")
        assertThat(r.fromAmount).isEqualByComparingTo("1300000")
        assertThat(r.toAmount).isEqualByComparingTo("1000")
        assertThat(r.amountKrw).isEqualByComparingTo("1300000")
        assertThat(r.toAmountKrw).isEqualByComparingTo("1300000")
        assertThat(r.spreadKrw).isEqualByComparingTo("0")   // 시세와 동일 → 전환비용 0
    }

    @Test
    fun `환전 스프레드는 OUT KRW - IN KRW 로 전환비용을 드러낸다`() {
        // 1,300,000원 지불하고 시세환산 1,287,000원어치만 받음 → 전환비용 13,000
        val (out, inn) = CashFlow.fxPair(user, a1, d(11),
            BigDecimal("1300000"), "KRW", BigDecimal("1300000"),
            BigDecimal("990"), "USD", BigDecimal("1287000"), "환전")
        val r = InternalFlowCalculator.build(listOf(out, inn), names).single()
        assertThat(r.toAmountKrw).isEqualByComparingTo("1287000")
        assertThat(r.spreadKrw).isEqualByComparingTo("13000")
    }

    @Test
    fun `이체는 동일통화 동일금액이라 스프레드 0`() {
        val (out, inn) = CashFlow.transferPair(user, a1, a2, d(10), BigDecimal("500"), "KRW", BigDecimal("500"), null)
        val r = InternalFlowCalculator.build(listOf(out, inn), names).single()
        assertThat(r.spreadKrw).isEqualByComparingTo("0")
        assertThat(r.toAmountKrw).isEqualByComparingTo("500")
    }

    @Test
    fun `여러 그룹은 날짜 내림차순-외부유형 제외-고아 레그 스킵`() {
        val (o1, i1) = CashFlow.transferPair(user, a1, a2, d(5), BigDecimal("100"), "KRW", BigDecimal("100"), null)
        val (o2, i2) = CashFlow.transferPair(user, a1, a2, d(20), BigDecimal("200"), "KRW", BigDecimal("200"), null)
        val deposit = CashFlow.create(user, a1, d(9), FlowType.DEPOSIT, BigDecimal("1"), "KRW", BigDecimal("1"), null)
        val flows = listOf(o1, i1, o2, i2, deposit, o1.let {  // 고아: IN 없는 OUT 하나 추가
            CashFlow.create(user, a1, d(1), FlowType.FX_OUT, BigDecimal("1"), "KRW", BigDecimal("1"), null, UUID.randomUUID())
        })
        val list = InternalFlowCalculator.build(flows, names)
        assertThat(list.map { it.date }).containsExactly(d(20), d(5))  // 내림차순, deposit·고아 제외
    }
}
