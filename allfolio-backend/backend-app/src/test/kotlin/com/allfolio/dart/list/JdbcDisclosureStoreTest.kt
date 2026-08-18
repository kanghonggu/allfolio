package com.allfolio.dart.list

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * SQL을 실행하지 않고 **가로챈다**. H2는 PostgreSQL 모드에서도 `ON CONFLICT`와 `RETURNING`을
 * 둘 다 지원하지 않고(실측), CI에는 Postgres가 없다. `PerformanceSnapshotDateTest`
 * (`unified-asset`)가 같은 이유로 같은 방식을 쓴다 — 그쪽 KDoc에 근거가 있다.
 *
 * 그래서 이 테스트가 못 잡는 것이 있다: **SQL이 Postgres에서 실제로 도는지**. 그건 구현 시
 * 로컬 Postgres로 1회 확인하고 커밋 메시지에 남긴다.
 */
class JdbcDisclosureStoreTest {

    /** 실행 SQL과 바인딩 인자를 모아 두는 fake. DataSource 없이 동작한다(super 호출 없음) */
    private class CapturingJdbc(
        /** `RETURNING`이 돌려줄 rcept_no. 테스트가 "이미 있던 건"을 흉내 내려면 비운다 */
        var returning: (String) -> List<String> = { listOf(it) },
    ) : JdbcTemplate() {
        val queries = mutableListOf<Pair<String, List<Any?>>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
            queries += sql to args.toList()
            return returning(args[0] as String) as List<T>
        }
    }

    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private fun row(rceptNo: String, stockCode: String? = "005930") = DisclosureInsert(
        rceptNo = rceptNo, corpCode = "00126380", corpName = "삼성전자",
        stockCode = stockCode, corpCls = "Y",
        reportNm = "단일판매ㆍ공급계약체결", reportNmNorm = "단일판매·공급계약체결",
        rceptDt = LocalDate.of(2026, 8, 18), flrNm = "삼성전자", rm = "유",
        isMaterial = true, materialTier = 1, isCorrection = false,
    )

    @Test
    fun `SQL에 ON CONFLICT DO NOTHING과 RETURNING이 들어간다`() {
        // 이 둘이 멱등성과 델타의 근거다. 하나라도 빠지면 재실행이 중복을 쌓거나
        // 델타가 전건이 되어 elestock을 매번 다시 부른다.
        val jdbc = CapturingJdbc()

        JdbcDisclosureStore(jdbc).insertIgnoringConflicts(listOf(row("A1")), now)

        val sql = jdbc.queries.single().first
        assertThat(sql).contains("INSERT INTO dart_disclosure")
        assertThat(sql).contains("ON CONFLICT (rcept_no) DO NOTHING")
        assertThat(sql).contains("RETURNING rcept_no")
    }

    @Test
    fun `14개 컬럼이 순서대로 바인딩된다`() {
        val jdbc = CapturingJdbc()

        JdbcDisclosureStore(jdbc).insertIgnoringConflicts(listOf(row("A1")), now)

        assertThat(jdbc.queries.single().second).containsExactly(
            "A1", "00126380", "삼성전자", "005930", "Y",
            "단일판매ㆍ공급계약체결", "단일판매·공급계약체결",
            LocalDate.of(2026, 8, 18), "삼성전자", "유",
            true, 1.toShort(), false, now,
        )
    }

    @Test
    fun `stock_code가 null이면 null로 바인딩된다`() {
        // 빈 문자열로 들어가면 부분 인덱스(WHERE stock_code IS NOT NULL)가 무용지물이 된다
        val jdbc = CapturingJdbc()

        JdbcDisclosureStore(jdbc).insertIgnoringConflicts(listOf(row("A1", stockCode = null)), now)

        assertThat(jdbc.queries.single().second[3]).isNull()
    }

    @Test
    fun `삽입된 행만 델타가 된다`() {
        // RETURNING이 빈 결과인 건 = 이미 있던 건
        val jdbc = CapturingJdbc(returning = { if (it == "A2") listOf(it) else emptyList() })

        val delta = JdbcDisclosureStore(jdbc)
            .insertIgnoringConflicts(listOf(row("A1"), row("A2")), now)

        assertThat(delta).containsExactly("A2")
    }

    @Test
    fun `한 배치 안의 중복은 한 번만 실행된다`() {
        // D-1과 D 범위가 겹쳐 같은 rcept_no가 두 번 올 수 있다.
        // ON CONFLICT는 같은 문(statement) 안의 중복을 못 막으므로 사전에 접어야 한다.
        val jdbc = CapturingJdbc()

        val delta = JdbcDisclosureStore(jdbc)
            .insertIgnoringConflicts(listOf(row("A1"), row("A1")), now)

        assertThat(jdbc.queries).hasSize(1)
        assertThat(delta).containsExactly("A1")
    }

    @Test
    fun `빈 목록이면 SQL을 아예 실행하지 않는다`() {
        val jdbc = CapturingJdbc()

        val delta = JdbcDisclosureStore(jdbc).insertIgnoringConflicts(emptyList(), now)

        assertThat(delta).isEmpty()
        assertThat(jdbc.queries).isEmpty()
    }

    @Test
    fun `선행 0이 붙은 rcept_no가 문자열 그대로 바인딩된다`() {
        // 숫자형으로 다루면 선행 0이 소실되어 원문 링크가 깨진다
        val jdbc = CapturingJdbc()

        JdbcDisclosureStore(jdbc).insertIgnoringConflicts(listOf(row("00260818000094")), now)

        assertThat(jdbc.queries.single().second[0]).isEqualTo("00260818000094")
    }
}
