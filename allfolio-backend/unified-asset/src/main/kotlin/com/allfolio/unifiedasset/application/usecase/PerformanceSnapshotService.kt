package com.allfolio.unifiedasset.application.usecase

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * NAV 스냅샷을 performance_daily에 기록한다.
 *
 * 호출자는 넷이고, sync 완료는 그중 하나다:
 * - 마감 워크플로우 S030 (NavSnapshotAction → DailyNavScheduler) — 워크플로우가 정한 일자
 * - 계좌 sync 성공 직후 (SyncAccountUseCase)
 * - 수동 자산 등록 직후 (AccountController.createAsset)
 * - CSV 임포트 직후 (AccountController.importCsv)
 */
@Service
class PerformanceSnapshotService(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * NAV 스냅샷을 performance_daily에 기록한다.
     *
     * **[date]에 기본값을 두지 않는다.** `LocalDate.now()`를 기본 인자로 두면 호출자가
     * 빠뜨렸을 때 조용히 UTC 날짜로 돌아가는데, 컨테이너가 UTC라 자정 KST 실행이 전날에
     * 앉는다. 증상이 "하루 밀림"이라 눈에 안 띄고, wf_job_log.ymd와 영원히 어긋난다.
     * 호출자 넷이 각자 무슨 날짜인지 알고 있으므로 전부 명시적으로 넘긴다.
     *
     * tenant_id = portfolio_id = userId (unified-asset은 사용자=포트폴리오 단위)
     */
    fun record(userId: UUID, nav: BigDecimal, date: LocalDate) {
        // 전일 NAV 조회 (daily_return 계산용)
        val prevNav: BigDecimal? = jdbc.query(
            """SELECT nav FROM performance_daily
               WHERE portfolio_id = ? AND date < ?
               ORDER BY date DESC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("nav") },
            userId, date,
        ).firstOrNull()

        // 최초 NAV 조회 (cumulative_return 계산용)
        val firstNav: BigDecimal? = jdbc.query(
            """SELECT nav FROM performance_daily
               WHERE portfolio_id = ?
               ORDER BY date ASC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("nav") },
            userId,
        ).firstOrNull()

        val dailyReturn = if (prevNav != null && prevNav > BigDecimal.ZERO)
            nav.subtract(prevNav).divide(prevNav, 6, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        val cumulativeReturn = if (firstNav != null && firstNav > BigDecimal.ZERO)
            nav.subtract(firstNav).divide(firstNav, 6, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        // UPSERT: 같은 날 sync를 여러 번 해도 덮어씀
        jdbc.update(
            """INSERT INTO performance_daily
                   (tenant_id, portfolio_id, date, nav, daily_return, cumulative_return, created_at)
               VALUES (?, ?, ?, ?, ?, ?, NOW())
               ON CONFLICT (tenant_id, portfolio_id, date)
               DO UPDATE SET
                   nav               = EXCLUDED.nav,
                   daily_return      = EXCLUDED.daily_return,
                   cumulative_return  = EXCLUDED.cumulative_return""",
            userId, userId, date, nav, dailyReturn, cumulativeReturn,
        )
        log.info("Performance snapshot recorded: userId=$userId date=$date nav=$nav daily=${dailyReturn.setScale(4, RoundingMode.HALF_UP)} cum=${cumulativeReturn.setScale(4, RoundingMode.HALF_UP)}")
    }
}
