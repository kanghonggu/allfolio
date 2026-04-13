package com.allfolio.unifiedasset.application.usecase

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Sync 완료 직후 호출 — 오늘의 NAV 스냅샷을 performance_daily에 기록한다.
 *
 * tenant_id = portfolio_id = userId (unified-asset은 사용자=포트폴리오 단위)
 */
@Service
class PerformanceSnapshotService(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun record(userId: UUID, nav: BigDecimal) {
        val today = LocalDate.now()

        // 전일 NAV 조회 (daily_return 계산용)
        val prevNav: BigDecimal? = jdbc.query(
            """SELECT nav FROM performance_daily
               WHERE portfolio_id = ? AND date < ?
               ORDER BY date DESC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("nav") },
            userId, today,
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
            userId, userId, today, nav, dailyReturn, cumulativeReturn,
        )
        log.info("Performance snapshot recorded: userId=$userId date=$today nav=$nav daily=${dailyReturn.setScale(4, RoundingMode.HALF_UP)} cum=${cumulativeReturn.setScale(4, RoundingMode.HALF_UP)}")
    }
}
