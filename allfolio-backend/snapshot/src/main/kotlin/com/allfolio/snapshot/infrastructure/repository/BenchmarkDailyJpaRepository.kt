package com.allfolio.snapshot.infrastructure.repository

import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyEntity
import com.allfolio.snapshot.infrastructure.entity.BenchmarkDailyId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface BenchmarkDailyJpaRepository : JpaRepository<BenchmarkDailyEntity, BenchmarkDailyId> {

    fun findTopByIdIndexTypeOrderByIdDateDesc(indexType: String): BenchmarkDailyEntity?

    fun findByIdIndexTypeAndIdDateBetween(
        indexType: String,
        from: LocalDate,
        to: LocalDate,
    ): List<BenchmarkDailyEntity>

    @Query(
        "SELECT b FROM BenchmarkDailyEntity b " +
        "WHERE b.id.indexType = :type AND b.id.date <= :before " +
        "ORDER BY b.id.date DESC"
    )
    fun findLatestOnOrBefore(
        @Param("type") indexType: String,
        @Param("before") before: LocalDate,
    ): List<BenchmarkDailyEntity>
}
