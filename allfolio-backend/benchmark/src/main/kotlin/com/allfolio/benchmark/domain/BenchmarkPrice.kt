package com.allfolio.benchmark.domain

import java.math.BigDecimal
import java.time.LocalDate

data class BenchmarkPrice(
    val date: LocalDate,
    val closePrice: BigDecimal,
) {
    init {
        if (closePrice < BigDecimal.ZERO) {
            throw BenchmarkException.negativePriceNotAllowed(closePrice)
        }
    }

    fun dailyReturn(previous: BenchmarkPrice): BigDecimal {
        require(previous.closePrice > BigDecimal.ZERO) {
            "Previous close price must be positive for return calculation"
        }
        return (closePrice - previous.closePrice)
            .divide(previous.closePrice, 10, java.math.RoundingMode.HALF_UP)
    }

    companion object {
        fun of(date: LocalDate, closePrice: BigDecimal): BenchmarkPrice =
            BenchmarkPrice(date, closePrice)

        fun of(date: LocalDate, closePrice: Double): BenchmarkPrice =
            BenchmarkPrice(date, BigDecimal.valueOf(closePrice))
    }
}
