package com.allfolio.unifiedasset.application.usecase

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 전 사용자 NAV 스냅샷 기록 (P3 #24에서 마감 워크플로우 S030 액션으로 편입).
 *
 * 자정 트리거는 backend-app ClosingScheduler → WfStepExecutor.runDaily가 담당하고,
 * 재동기화(구 1단계)는 S010 액션(DailyAccountSyncer)으로 분리됐다 — 이 클래스는 NAV 파트만 남음.
 *
 * SyncAccountUseCase가 sync 성공 시 이미 스냅샷을 UPSERT하지만, 이 명시적 패스는 syncable
 * 계좌가 없는 사용자·전부 실패한 사용자까지 마지막 값으로라도 스냅샷을 보장하는 안전망이다.
 *
 * **이 패스가 남기는 건 그 날의 첫 값이지 최종값이 아니다.** 자정 KST 실행이 쓰는 일자는
 * 그날 자신(ctx.ymd)이라, 이후 같은 날 도는 sync가 (tenant,portfolio,date) UPSERT로 계속
 * 덮어쓴다. 그래서 하루가 끝난 뒤 performance_daily에 남는 값은 그날 마지막 sync의 것이다.
 * (AF-106 이후로는 sync 경로도 통화별 내역을 넘기고 환산은 record()가 통화별로 한 번씩
 * 하므로, 두 경로의 KRW 라운딩이 같아져 덮어써도 값이 어긋나지 않는다.)
 */
@Component
class DailyNavScheduler(
    private val jdbc: JdbcTemplate,
    private val snapshotService: PerformanceSnapshotService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param ymd 마감 워크플로우가 정한 일자. **`LocalDate.now()`로 대체하지 말 것** —
     *            자정 KST 실행은 UTC로 전날이라 스냅샷이 하루 밀린다.
     * @return 스냅샷 기록 사용자 수
     */
    fun recordDailySnapshots(ymd: LocalDate): Int {
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

        // 환산은 record()가 통화별로 한 번씩 한다 — 여기서 접으면 통화 내역이 사라져
        // nav_currency_daily에 쓸 게 없어진다 (AF-106).
        val byUser: Map<UUID, Map<String, BigDecimal>> = perCurrency
            .groupBy { it.first }
            .mapValues { (_, rows) ->
                // 같은 (user, currency)는 SQL이 이미 GROUP BY로 합쳤으므로 키 충돌이 없다
                rows.associate { (_, currency, value) -> currency.trim().uppercase() to value }
            }

        if (byUser.isEmpty()) {
            log.debug("[DailyNavScheduler] no users with assets, skipping")
            return 0
        }

        log.info("[DailyNavScheduler] recording snapshots for {} users", byUser.size)
        byUser.forEach { (userId, navByCurrency) ->
            runCatching { snapshotService.record(userId, navByCurrency, ymd) }
                .onFailure { e -> log.error("[DailyNavScheduler] failed userId={}", userId, e) }
        }
        log.info("[DailyNavScheduler] done")
        return byUser.size
    }
}
