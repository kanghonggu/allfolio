package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class IndexPeriodReturnTest {

    private val jan1 = LocalDate.of(2026, 1, 1)
    private fun row(date: LocalDate, close: String) = date to BigDecimal(close)

    @Test
    fun `기저는 기간 시작 이전(포함) 마지막 종가다 - 그 뒤 종가가 아니다`() {
        // 12/30 4000, 1/2 4400, 오늘 5000 → 12/30 기준 +25.00 (1/2 기준이면 +13.64)
        val rows = listOf(row(jan1.minusDays(2), "4000"), row(jan1.plusDays(1), "4400"), row(jan1.plusDays(200), "5000"))

        assertEquals(0, BigDecimal("25.00").compareTo(IndexPeriodReturn.percent(rows, jan1)))
    }

    @Test
    fun `기간 시작 당일 종가가 있으면 그날이 기저다`() {
        val rows = listOf(row(jan1.minusDays(1), "4000"), row(jan1, "4100"), row(jan1.plusDays(10), "4510"))

        assertEquals(0, BigDecimal("10.00").compareTo(IndexPeriodReturn.percent(rows, jan1)))
    }

    @Test
    fun `기간 시작 이전 종가가 없으면 읽은 첫 종가가 기저다`() {
        val rows = listOf(row(jan1.plusDays(3), "4000"), row(jan1.plusDays(10), "4400"))

        assertEquals(0, BigDecimal("10.00").compareTo(IndexPeriodReturn.percent(rows, jan1)))
    }

    @Test
    fun `종가가 2건 미만이거나 기저가 0 이하면 null`() {
        assertNull(IndexPeriodReturn.percent(listOf(row(jan1, "4000")), jan1))
        assertNull(IndexPeriodReturn.percent(listOf(row(jan1, "0"), row(jan1.plusDays(1), "4000")), jan1))
    }

    @Test
    fun `저장소에서 기간 시작 14일 전부터 오늘까지 읽는다`() {
        var asked: Pair<LocalDate, LocalDate>? = null
        val store = object : BenchmarkDailyStore {
            override fun latestDate(type: BenchmarkType): LocalDate? = null
            override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) = Unit
            override fun series(type: BenchmarkType, from: LocalDate, to: LocalDate): List<Pair<LocalDate, BigDecimal>> {
                asked = from to to
                return listOf(row(jan1.minusDays(1), "4000"), row(jan1.plusDays(5), "4200"))
            }
        }
        val today = jan1.plusDays(5)

        val ret = IndexPeriodReturn.of(store, BenchmarkType.KOSPI, jan1, today)

        assertEquals(jan1.minusDays(14) to today, asked)
        assertEquals(0, BigDecimal("5.00").compareTo(ret))
    }
}
