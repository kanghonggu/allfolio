package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.FxConverter
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 전 사용자 NAV 스냅샷 기록 (P3 #24에서 마감 워크플로우 S030 액션으로 편입).
 *
 * 자정 트리거는 GitHub Actions 크론(.github/workflows/closing.yml) → /api/internal/scheduler/closing
 * → WfStepExecutor.runDaily가 담당한다. 인스턴스 안의 ClosingScheduler는 기본 꺼짐
 * (CLOSING_SCHEDULER_ENABLED) — 무료 Render는 자정에 잠들어 있어 @Scheduled가 성립하지 않는다.
 * 재동기화(구 1단계)는 S010 액션(DailyAccountSyncer)으로 분리됐다 — 이 클래스는 NAV 파트만 남음.
 *
 * **이 패스가 자기 일자의 마지막 기록자다.** 받는 일자는 ctx.ymd가 아니라 ctx.ymd.minusDays(1)
 * (NavSnapshotAction 참조) — 자정 KST가 읽는 값은 직전일이 끝난 값이기 때문이다. 그 일자 X에
 * 대화형 sync가 쓰는 건 전부 X 당일(00:00~24:00 KST)에 일어나고 이 패스는 X+1 00:05에 도니,
 * (tenant,portfolio,date) UPSERT의 순서상 항상 이쪽이 마지막이다. 통화별 1회 환산이라
 * 자산별 환산 합보다 정확한데(KRW 라운딩 차이로 수 원 다를 수 있다) 그 이점이 그대로 남는다.
 *
 * S010이 도는 동안 sync 부수효과가 같은 날 행을 하나 더 만들던 문제는 SyncAccountUseCase가
 * SyncTrigger.SCHEDULED에서 스냅샷을 안 쓰게 해서 닫았다 — 마감 중 기록자는 이 패스 하나다.
 *
 * **한계 둘.** (1) ua_assets에 행이 없는 사용자는 navByUser에 안 잡혀 스냅샷이 아예 안 남는다.
 * (2) S030은 S020←S010 게이트 뒤라, 동기화가 전량 실패한 날은 이 패스가 돌지 않는다 — 의도된
 * 동작이다(그날 NAV는 어제 값 그대로라 "안 움직인 날"이라는 거짓 관측이 되고, TWR은 결측은
 * 견디지만 거짓 관측은 못 견딘다). **이 클래스를 "전 사용자 안전망"이라고 부르지 말 것** —
 * 덮는 건 syncable 계좌가 없는 사용자까지고(그쪽은 total=0이라 게이트에 안 걸린다), 전량
 * 실패한 사용자는 일부러 안 덮는다.
 */
@Component
class DailyNavScheduler(
    private val jdbc: JdbcTemplate,
    private val snapshotService: PerformanceSnapshotService,
    private val fx: FxConverter,
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
            runCatching { snapshotService.record(userId, nav, ymd) }
                .onFailure { e -> log.error("[DailyNavScheduler] failed userId={}", userId, e) }
        }
        log.info("[DailyNavScheduler] done")
        return navByUser.size
    }
}
