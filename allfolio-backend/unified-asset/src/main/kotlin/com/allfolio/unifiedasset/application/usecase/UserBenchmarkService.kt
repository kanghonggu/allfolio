package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/** 사용자 벤치마크 설정 (R1 #35) — user_benchmark 단일행 UPSERT/DELETE */
@Service
class UserBenchmarkService(private val jdbc: JdbcTemplate) {

    fun get(userId: UUID): BenchmarkType? =
        jdbc.query(
            "SELECT index_type FROM user_benchmark WHERE user_id = ?",
            { rs, _ -> rs.getString("index_type") },
            userId,
        ).firstOrNull()?.let { stored ->
            BenchmarkType.entries.firstOrNull { it.name == stored }
        }

    fun set(userId: UUID, type: BenchmarkType?) {
        if (type == null) {
            jdbc.update("DELETE FROM user_benchmark WHERE user_id = ?", userId)
        } else {
            jdbc.update(
                """INSERT INTO user_benchmark (user_id, index_type, updated_at)
                   VALUES (?, ?, NOW())
                   ON CONFLICT (user_id) DO UPDATE SET index_type = EXCLUDED.index_type, updated_at = NOW()""",
                userId, type.name,
            )
        }
    }
}
