package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.FxConverter
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
    private val fx: FxConverter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    fun recordDailySnapshots() {
        // 통화별로 합산한 뒤 KRW로 환산해야 통화가 섞인 사용자의 NAV가 올바르다.
        // (SUM(current_value)만으로는 KRW·USD 금액을 그대로 더해 무의미한 값이 나온다)
        val perCurrency = jdbc.query(
            "SELECT user_id, currency, SUM(current_value) AS v FROM ua_assets GROUP BY user_id, currency"
        ) { rs, _ ->
            Triple(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("currency") ?: "KRW",
                rs.getBigDecimal("v") ?: BigDecimal.ZERO,
            )
        }

        val navByUser = perCurrency
            .groupBy { it.first }
            .mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { acc, (_, currency, value) -> acc + fx.toKrw(value, currency) }
            }

        if (navByUser.isEmpty()) {
            log.debug("[DailyNavScheduler] no users with assets, skipping")
            return
        }

        log.info("[DailyNavScheduler] recording snapshots for {} users", navByUser.size)
        navByUser.forEach { (userId, nav) ->
            runCatching { snapshotService.record(userId, nav) }
                .onFailure { e -> log.error("[DailyNavScheduler] failed userId={}", userId, e) }
        }
        log.info("[DailyNavScheduler] done")
    }
}
