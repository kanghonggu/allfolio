package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

@Component
class JdbcBenchmarkDailyStore(private val jdbc: JdbcTemplate) : BenchmarkDailyStore {

    override fun latestDate(type: BenchmarkType): LocalDate? =
        jdbc.query(
            "SELECT MAX(date) AS d FROM benchmark_daily WHERE index_type = ?",
            { rs, _ -> rs.getDate("d")?.toLocalDate() },
            type.name,
        ).firstOrNull()

    override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) {
        jdbc.batchUpdate(
            """INSERT INTO benchmark_daily (index_type, date, close_value)
               VALUES (?, ?, ?)
               ON CONFLICT (index_type, date) DO UPDATE SET close_value = EXCLUDED.close_value""",
            rows,
            500,
        ) { ps, (date, close) ->
            ps.setString(1, type.name)
            ps.setObject(2, date)
            ps.setBigDecimal(3, close)
        }
    }

    override fun series(type: BenchmarkType, from: LocalDate, to: LocalDate): List<Pair<LocalDate, BigDecimal>> =
        jdbc.query(
            """SELECT date, close_value FROM benchmark_daily
               WHERE index_type = ? AND date BETWEEN ? AND ?
               ORDER BY date ASC""",
            { rs, _ -> rs.getDate("date").toLocalDate() to rs.getBigDecimal("close_value") },
            type.name, from, to,
        )
}
