package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SpecialTransactionCalculatorTest {

    private val userId = UUID.randomUUID()
    private val acctId = UUID.randomUUID()
    private val acctNames = mapOf(acctId to "한투")

    private fun flow(type: FlowType, krw: String, day: Int = 5) = CashFlow.create(
        userId = userId, accountId = acctId, flowDate = LocalDate.of(2026, 6, day),
        type = type, amount = BigDecimal(krw), currency = "KRW", amountKrw = BigDecimal(krw), memo = "메모",
    )
    private fun trade(type: String, total: String, day: Int = 5) =
        TradeCashRecord(LocalDate.of(2026, 6, day), type, "종목", "한투", BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO)

    @Test
    fun `총자산 대비 임계 이상 이동은 대규모 이동으로 잡힌다`() {
        val s = SpecialTransactionCalculator.build(
            listOf(flow(FlowType.DEPOSIT, "150000"), flow(FlowType.DEPOSIT, "50000", day = 6)),
            emptyList(), acctNames, BigDecimal("1000000"),
        )
        assertThat(s.largeMovements).hasSize(1)
        assertThat(s.largeMovements[0].amountKrw).isEqualByComparingTo("150000")
        assertThat(s.largeMovements[0].type).isEqualTo("입금")
    }

    @Test
    fun `총자산 0이면 대규모 이동은 없다`() {
        val s = SpecialTransactionCalculator.build(
            listOf(flow(FlowType.DEPOSIT, "150000")), emptyList(), acctNames, BigDecimal.ZERO,
        )
        assertThat(s.largeMovements).isEmpty()
    }

    @Test
    fun `출금과 매수는 음수 부호로 표시된다`() {
        val s = SpecialTransactionCalculator.build(
            listOf(flow(FlowType.WITHDRAWAL, "200000")),
            listOf(trade("BUY", "300000")),
            acctNames, BigDecimal("1000000"),
        )
        val byType = s.largeMovements.associateBy { it.type }
        assertThat(byType["출금"]!!.amountKrw).isEqualByComparingTo("-200000")
        assertThat(byType["매수대금"]!!.amountKrw).isEqualByComparingTo("-300000")
    }

    @Test
    fun `미매핑 거래유형은 미분류로 잡히고 알려진 유형은 아니다`() {
        val s = SpecialTransactionCalculator.build(
            emptyList(),
            listOf(trade("MARGIN", "10000"), trade("BUY", "10000"), trade("DIVIDEND", "10000")),
            acctNames, BigDecimal("1000000"),
        )
        assertThat(s.unclassified).hasSize(1)
        assertThat(s.unclassified[0].tradeType).isEqualTo("MARGIN")
    }

    @Test
    fun `대규모 이동은 금액 절대값 내림차순 정렬`() {
        val s = SpecialTransactionCalculator.build(
            listOf(flow(FlowType.DEPOSIT, "150000"), flow(FlowType.WITHDRAWAL, "300000", day = 7)),
            emptyList(), acctNames, BigDecimal("1000000"),
        )
        assertThat(s.largeMovements.map { it.amountKrw.abs().toInt() }).containsExactly(300000, 150000)
    }
}
