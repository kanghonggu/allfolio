package com.allfolio.snapshot.infrastructure.repository

import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyEntity
import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyId
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface BenchmarkDailyJpaRepository : JpaRepository<BenchmarkDailyEntity, BenchmarkDailyId> {

    fun findTopByIdIndexTypeOrderByIdDateDesc(indexType: String): BenchmarkDailyEntity?

    fun findByIdIndexTypeAndIdDateBetween(
        indexType: String,
        from: LocalDate,
        to: LocalDate,
    ): List<BenchmarkDailyEntity>

    fun findTopByIdIndexTypeAndIdDateLessThanEqualOrderByIdDateDesc(
        indexType: String,
        date: LocalDate,
    ): BenchmarkDailyEntity?
}
