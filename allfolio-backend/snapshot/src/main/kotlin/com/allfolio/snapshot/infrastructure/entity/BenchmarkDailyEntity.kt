package com.allfolio.snapshot.infrastructure.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "benchmark_daily")
class BenchmarkDailyEntity(
    @EmbeddedId
    val id: BenchmarkDailyId,

    @Column(name = "close_value", nullable = false, precision = 30, scale = 10)
    val closeValue: BigDecimal,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

@Embeddable
data class BenchmarkDailyId(
    @Column(name = "index_type", length = 20)
    val indexType: String,
    val date: LocalDate,
) : java.io.Serializable
