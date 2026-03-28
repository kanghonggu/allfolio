package com.allfolio.benchmark.domain

import java.time.LocalDate
import java.time.LocalDateTime

class Benchmark private constructor(
    val id: BenchmarkId,
    val symbol: String,
    val currency: String,
    val createdAt: LocalDateTime,
    name: String,
    active: Boolean,
) {
    var name: String = name
        private set

    var active: Boolean = active
        private set

    fun rename(newName: String) {
        if (newName.isBlank()) throw BenchmarkException.blankName()
        name = newName
    }

    fun activate() {
        if (active) throw BenchmarkException.alreadyActive(id)
        active = true
    }

    fun deactivate() {
        if (!active) throw BenchmarkException.alreadyInactive(id)
        active = false
    }

    fun isActive(): Boolean = active

    /**
     * 동일 date 중복 여부 검증.
     * 실제 저장은 infrastructure 책임이며, 도메인은 검증 규칙만 제공한다.
     */
    fun validateNoDuplicateDate(existingDates: Set<LocalDate>, newDate: LocalDate) {
        if (existingDates.contains(newDate)) {
            throw BenchmarkException.duplicateDate(newDate)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Benchmark) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Benchmark(id=$id, symbol=$symbol, currency=$currency, active=$active)"

    companion object {
        fun create(
            name: String,
            symbol: String,
            currency: String,
        ): Benchmark {
            if (symbol.isBlank()) throw BenchmarkException.blankSymbol()
            if (name.isBlank()) throw BenchmarkException.blankName()
            if (currency.isBlank()) throw BenchmarkException.blankCurrency()

            return Benchmark(
                id = BenchmarkId.newId(),
                name = name,
                symbol = symbol.uppercase(),
                currency = currency.uppercase(),
                createdAt = LocalDateTime.now(),
                active = true,
            )
        }

        fun reconstruct(
            id: BenchmarkId,
            name: String,
            symbol: String,
            currency: String,
            createdAt: LocalDateTime,
            active: Boolean,
        ): Benchmark = Benchmark(
            id = id,
            name = name,
            symbol = symbol,
            currency = currency,
            createdAt = createdAt,
            active = active,
        )
    }
}
