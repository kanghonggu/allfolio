package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.usecase.NavHistorySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** performance_daily(사용자 단위: portfolio_id = userId) NAV 시계열 조회 */
@Component
class JdbcNavHistorySource(private val jdbc: JdbcTemplate) : NavHistorySource {
    override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate): List<NavPoint> =
        jdbc.query(
            """SELECT date, nav FROM performance_daily
               WHERE portfolio_id = ? AND date BETWEEN ? AND ?
               ORDER BY date ASC""",
            { rs, _ -> NavPoint(rs.getDate("date").toLocalDate(), rs.getBigDecimal("nav")) },
            userId, from, to,
        )
}
