package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.FxConverter
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * 전 사용자 NAV 스냅샷 기록 (P3 #24에서 마감 워크플로우 S030 액션으로 편입).
 *
 * 자정 트리거는 backend-app ClosingScheduler → WfStepExecutor.runDaily가 담당하고,
 * 재동기화(구 1단계)는 S010 액션(DailyAccountSyncer)으로 분리됐다 — 이 클래스는 NAV 파트만 남음.
 *
 * SyncAccountUseCase가 sync 성공 시 이미 스냅샷을 UPSERT하지만, 이 명시적 패스는 syncable
 * 계좌가 없는 사용자·전부 실패한 사용자까지 마지막 값으로라도 스냅샷을 보장하는 안전망이다.
 * 같은 날은 (tenant,portfolio,date) UPSERT라 이 패스가 값을 확정한다. (sync 부수효과의
 * 자산별 환산 합과 여기 통화별 환산 합은 KRW 라운딩 방식 차이로 수 원 다를 수 있으나,
 * 통화별 1회 환산인 이 패스 값이 더 정확하고 최종값이 된다.)
 */
@Component
class DailyNavScheduler(
    private val jdbc: JdbcTemplate,
    private val snapshotService: PerformanceSnapshotService,
    private val fx: FxConverter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return 스냅샷 기록 사용자 수 */
    fun recordDailySnapshots(): Int {
        // 통화별로 합산한 뒤 KRW로 환산해야 통화가 섞인 사용자의 NAV가 올바르다.
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
            return 0
        }

        log.info("[DailyNavScheduler] recording snapshots for {} users", navByUser.size)
        navByUser.forEach { (userId, nav) ->
            runCatching { snapshotService.record(userId, nav) }
                .onFailure { e -> log.error("[DailyNavScheduler] failed userId={}", userId, e) }
        }
        log.info("[DailyNavScheduler] done")
        return navByUser.size
    }
}
