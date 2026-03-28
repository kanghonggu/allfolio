package com.allfolio.benchmark.domain

import java.util.UUID

data class BenchmarkId(val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        fun newId(): BenchmarkId = BenchmarkId(UUID.randomUUID())

        fun of(value: String): BenchmarkId = BenchmarkId(UUID.fromString(value))

        fun of(value: UUID): BenchmarkId = BenchmarkId(value)
    }
}
