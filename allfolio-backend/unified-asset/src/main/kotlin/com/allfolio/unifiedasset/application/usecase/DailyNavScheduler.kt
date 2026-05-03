package com.allfolio.unifiedasset.application.usecase

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * 매일 자정 KST에 모든 사용자의 NAV를 performance_daily에 기록한다.
 * Sync를 안 한 날에도 마지막 known 가격 기준으로 이력이 쌓인다.
 */
@Component
class DailyNavScheduler(
    private val jdbc: JdbcTemplate,
    private val snapshotService: PerformanceSnapshotService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    fun recordDailySnapshots() {
        val rows = jdbc.query(
            "SELECT user_id, SUM(current_value) AS nav FROM ua_assets GROUP BY user_id"
        ) { rs, _ ->
            Pair(
                UUID.fromString(rs.getString("user_id")),
                rs.getBigDecimal("nav") ?: BigDecimal.ZERO,
            )
        }

        if (rows.isEmpty()) {
            log.debug("[DailyNavScheduler] no users with assets, skipping")
            return
        }

        log.info("[DailyNavScheduler] recording snapshots for {} users", rows.size)
        rows.forEach { (userId, nav) ->
            runCatching { snapshotService.record(userId, nav) }
                .onFailure { e -> log.error("[DailyNavScheduler] failed userId={}", userId, e) }
        }
        log.info("[DailyNavScheduler] done")
    }
}
