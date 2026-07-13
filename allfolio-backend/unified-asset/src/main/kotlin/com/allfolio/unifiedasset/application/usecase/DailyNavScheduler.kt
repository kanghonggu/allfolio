package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.FxConverter
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * 매일 자정 KST 배치:
 *  1) 자동조회 대상 계좌를 전부 재동기화(DailyAccountSyncer) → ua_assets 최신 시세 반영
 *  2) 모든 사용자의 NAV를 performance_daily에 스냅샷(통화 혼재 → KRW 환산)
 *
 * 스냅샷 파트는 sync 결과와 무관하게 항상 실행한다. SyncAccountUseCase가 sync 성공 시
 * 이미 스냅샷을 UPSERT하지만, 여기 명시적 패스는 syncable 계좌가 없는 사용자·전부 실패한
 * 사용자까지 마지막 값으로라도 스냅샷을 보장하는 안전망이다(UPSERT라 멱등).
 */
@Component
class DailyNavScheduler(
    private val jdbc: JdbcTemplate,
    private val snapshotService: PerformanceSnapshotService,
    private val fx: FxConverter,
    private val dailyAccountSyncer: DailyAccountSyncer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    fun recordDailySnapshots() {
        // 1) 최신 시세로 계좌 재동기화 (배치 실패해도 스냅샷은 진행)
        runCatching { dailyAccountSyncer.syncAll() }
            .onFailure { e -> log.error("[DailyNavScheduler] account sync batch failed", e) }

        // 2) 통화별로 합산한 뒤 KRW로 환산해야 통화가 섞인 사용자의 NAV가 올바르다.
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
