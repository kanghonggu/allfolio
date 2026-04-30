package com.allfolio.market

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class MaturityAlertScheduler(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ALERT_DAYS = listOf(30L, 7L, 1L)

    // 매일 오전 7시 — 만기 임박 자산 로그 출력 (추후 알림 채널 연동 확장)
    @Scheduled(cron = "0 0 7 * * *")
    fun checkMaturityAlerts() {
        val today = LocalDate.now()
        ALERT_DAYS.forEach { days ->
            val targetDate = today.plusDays(days)
            val assets = jdbc.query(
                """SELECT id, user_id, name, maturity_date
                   FROM ua_assets
                   WHERE maturity_date = ? AND liquidity_type = 'ILLIQUID'""",
                { rs, _ ->
                    Triple(
                        rs.getString("user_id"),
                        rs.getString("name"),
                        rs.getDate("maturity_date").toLocalDate()
                    )
                },
                targetDate,
            )
            assets.forEach { (userId, name, date) ->
                log.warn("MaturityAlert: userId=$userId asset='$name' matures=$date (D-$days)")
            }
        }
    }
}
