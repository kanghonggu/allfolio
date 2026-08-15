package com.allfolio.unifiedasset.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
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
 * 검증 방식은 JdbcTemplate 가로채기 — 이 서비스는 JdbcTemplate 하나에만 의존한다.
 * H2 통합 경로는 막혀 있다: performance_daily 엔티티는 :snapshot 모듈 소유라
 * :unified-asset 테스트 스키마에 생성되지 않고, INSERT도 Postgres 전용 ON CONFLICT다.
 * 인자 조립만 순수 함수로 떼어내 검증하면 record()가 그걸 실제로 쓰는지는 못 박지 못한다.
 *
 * 오늘과 절대 겹치지 않는 과거 날짜를 넘긴다 — LocalDate.now()로 되돌리면 반드시 깨진다.
 */
class PerformanceSnapshotDateTest {

    /** 실행 SQL과 바인딩 인자를 그대로 모아두는 fake. DataSource 없이 동작한다(super 호출 없음). */
    private class CapturingJdbcTemplate : JdbcTemplate() {
        val updates = mutableListOf<Pair<String, List<Any?>>>()
        val queries = mutableListOf<Pair<String, List<Any?>>>()

        override fun update(sql: String, vararg args: Any?): Int {
            updates += sql to args.toList()
            return 1
        }

        // 이전 NAV·최초 NAV 조회는 빈 결과로 — daily/cumulative가 0이 되어 산술이 끼어들지 않는다
        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
            queries += sql to args.toList()
            return emptyList()
        }
    }

    @Test
    fun `호출자가 넘긴 날짜로 기록한다 — 실행 시점 날짜가 아니라`() {
        val jdbc = CapturingJdbcTemplate()
        val userId = UUID.randomUUID()
        val ymd = LocalDate.of(2024, 2, 29)  // 오늘일 수 없는 과거 날짜

        PerformanceSnapshotService(jdbc).record(userId, BigDecimal("1234567"), ymd)

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

        PerformanceSnapshotService(jdbc).record(userId, BigDecimal("1000"), ymd)

        // `date < ?` 경계가 실행 시점으로 새면, 마감을 소급 실행할 때 미래 행을 전일로 집어든다
        val (_, args) = jdbc.queries.single { it.first.contains("date < ?") }
        assertEquals(ymd, args.last()) { "전일 NAV 조회 경계가 호출자 날짜와 다르다: args=$args" }
    }
}
