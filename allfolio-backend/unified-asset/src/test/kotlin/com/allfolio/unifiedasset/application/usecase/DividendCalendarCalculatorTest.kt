package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.DividendRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class DividendCalendarCalculatorTest {
    // tax=0 이므로 net == gross. ttmNet 기대값은 gross 합.
    private fun rec(month: Int, name: String, sym: String?, gross: String, tax: String = "0", acct: String = "계좌", provider: String = "KIS") =
        DividendRecord(LocalDate.of(2026, month, 15), name, sym, acct, provider, BigDecimal(gross), BigDecimal(tax))

    @Test
    fun `월배당은 12회 지급-월1~12로 분류된다`() {
        val ttm = (1..12).map { rec(it, "리얼티", "O", "100") }
        val e = DividendCalendarCalculator.build(ttm).first()
        assertThat(e.cadence).isEqualTo("월배당")
        assertThat(e.payCount).isEqualTo(12)
        assertThat(e.paidMonths).containsExactly(1,2,3,4,5,6,7,8,9,10,11,12)
    }

    @Test
    fun `분기-반기-연1회-비정기 분류`() {
        assertThat(DividendCalendarCalculator.build(listOf(3,6,9,12).map { rec(it,"A","A","10") }).first().cadence).isEqualTo("분기배당")
        assertThat(DividendCalendarCalculator.build(listOf(6,12).map { rec(it,"B","B","10") }).first().cadence).isEqualTo("반기배당")
        assertThat(DividendCalendarCalculator.build(listOf(rec(5,"C","C","10"))).first().cadence).isEqualTo("연 1회/단발")
        assertThat(DividendCalendarCalculator.build(listOf(1,2,7).map { rec(it,"D","D","10") }).first().cadence).isEqualTo("비정기")
    }

    @Test
    fun `같은 종목 다계좌는 합산되고 lastPayDate는 최대-정렬은 ttmNet 내림차순`() {
        val ttm = listOf(
            rec(3, "삼성", "005930", "100", acct = "A"),
            rec(9, "삼성", "005930", "300", acct = "B"),
            rec(6, "애플", "AAPL", "500"),
        )
        val list = DividendCalendarCalculator.build(ttm)
        assertThat(list.map { it.stockName }).containsExactly("애플", "삼성")  // 500 > 400
        val samsung = list.first { it.stockName == "삼성" }
        assertThat(samsung.payCount).isEqualTo(2)
        assertThat(samsung.ttmNet).isEqualByComparingTo(BigDecimal("400"))
        assertThat(samsung.lastPayDate).isEqualTo(LocalDate.of(2026, 9, 15))
        assertThat(samsung.paidMonths).containsExactly(3, 9)
    }
}
