package com.allfolio.unifiedasset.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 스냅샷 날짜는 **호출자가 정한다**.
 *
 * 컨테이너가 UTC라 `LocalDate.now()`는 자정 KST 실행 시점에 전날을 돌려준다. 그러면
 * wf_job_log.ymd는 D인데 performance_daily.date는 D−1에 앉아 로그와 데이터가 영원히
 * 어긋난다. 그래서 record()가 받은 날짜를 그대로 써야 한다.
 *
 * 검증 방식은 JdbcTemplate 가로채기 — 이 서비스의 SQL은 JdbcTemplate 하나로만 나간다.
 * H2 통합 경로는 막혀 있다: performance_daily 엔티티는 :snapshot 모듈 소유라
 * :unified-asset 테스트 스키마에 생성되지 않고, INSERT도 Postgres 전용 ON CONFLICT다.
 * 인자 조립만 순수 함수로 떼어내 검증하면 record()가 그걸 실제로 쓰는지는 못 박지 못한다.
 *
 * 오늘과 절대 겹치지 않는 과거 날짜를 넘긴다 — LocalDate.now()로 되돌리면 반드시 깨진다.
 *
 * 두 테이블(performance_daily + nav_currency_daily) 계약은
 * [PerformanceSnapshotCurrencyTest]가 못 박는다.
 */
class PerformanceSnapshotDateTest {

    @Test
    fun `호출자가 넘긴 날짜로 기록한다 — 실행 시점 날짜가 아니라`() {
        val jdbc = CapturingJdbcTemplate()
        val userId = UUID.randomUUID()
        val ymd = LocalDate.of(2024, 2, 29)  // 오늘일 수 없는 과거 날짜

        snapshotService(jdbc).record(userId, mapOf("KRW" to BigDecimal("1234567")), ymd)

        val (sql, args) = jdbc.updates.singleOrNull()
            ?: error("INSERT가 가로채지지 않았다 — updates=${jdbc.updates.size}")
        assertTrue(sql.contains("INSERT INTO performance_daily")) { "예상 밖 SQL: $sql" }
        // (tenant_id, portfolio_id, date, nav, daily_return, cumulative_return)
        assertEquals(ymd, args[2]) { "performance_daily.date가 호출자 날짜와 다르다: args=$args" }
    }

    @Test
    fun `전일 NAV 조회도 같은 날짜를 기준으로 한다`() {
        val jdbc = CapturingJdbcTemplate()
        val userId = UUID.randomUUID()
        val ymd = LocalDate.of(2024, 2, 29)

        snapshotService(jdbc).record(userId, mapOf("KRW" to BigDecimal("1000")), ymd)

        // `date < ?` 경계가 실행 시점으로 새면, 마감을 소급 실행할 때 미래 행을 전일로 집어든다
        val (_, args) = jdbc.queries.single { it.first.contains("date < ?") }
        assertEquals(ymd, args.last()) { "전일 NAV 조회 경계가 호출자 날짜와 다르다: args=$args" }
    }
}
