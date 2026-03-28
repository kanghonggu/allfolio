package com.allfolio.benchmark.domain

import com.allfolio.common.domain.DomainException

class BenchmarkException(
    errorCode: String,
    message: String,
    cause: Throwable? = null,
) : DomainException(errorCode, message, cause) {

    companion object {
        fun notFound(id: BenchmarkId) =
            BenchmarkException("BENCHMARK_NOT_FOUND", "Benchmark not found: $id")

        fun duplicateSymbol(symbol: String) =
            BenchmarkException("BENCHMARK_DUPLICATE_SYMBOL", "Benchmark symbol already exists: $symbol")

        fun blankSymbol() =
            BenchmarkException("BENCHMARK_BLANK_SYMBOL", "Benchmark symbol must not be blank")

        fun blankName() =
            BenchmarkException("BENCHMARK_BLANK_NAME", "Benchmark name must not be blank")

        fun blankCurrency() =
            BenchmarkException("BENCHMARK_BLANK_CURRENCY", "Benchmark currency must not be blank")

        fun negativePriceNotAllowed(price: java.math.BigDecimal) =
            BenchmarkException("BENCHMARK_NEGATIVE_PRICE", "Benchmark close price must be non-negative: $price")

        fun duplicateDate(date: java.time.LocalDate) =
            BenchmarkException("BENCHMARK_DUPLICATE_DATE", "Benchmark price already exists for date: $date")

        fun alreadyInactive(id: BenchmarkId) =
            BenchmarkException("BENCHMARK_ALREADY_INACTIVE", "Benchmark is already inactive: $id")

        fun alreadyActive(id: BenchmarkId) =
            BenchmarkException("BENCHMARK_ALREADY_ACTIVE", "Benchmark is already active: $id")
    }
}
