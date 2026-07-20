package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import java.math.BigDecimal
import java.time.LocalDate

/** benchmark_daily 접근 포트 (구현: JdbcTemplate — snapshot 모듈 비의존) */
interface BenchmarkDailyStore {
    fun latestDate(type: BenchmarkType): LocalDate?
    fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>)
    fun series(type: BenchmarkType, from: LocalDate, to: LocalDate): List<Pair<LocalDate, BigDecimal>>
}
