package com.allfolio.dart.corp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * SQL을 실행하지 않고 **가로챈다** — 근거는 `JdbcDisclosureStoreTest`(Task 8)와 같다. H2는
 * PostgreSQL 모드에서도 `ON CONFLICT`를 지원하지 않고(실측) CI에는 Postgres가 없다.
 *
 * 이 테스트가 못 잡는 것: SQL이 Postgres에서 실제로 도는지, INSERT 컬럼 목록의 순서(둘 다
 * VARCHAR/DATE라 순서가 바뀌어도 바인딩만 맞으면 Postgres도 조용히 받는다). 그건 Task 1
 * 마이그레이션 대조로 담보한다.
 */
class JdbcCorpMapStoreTest {

    /** 실행 SQL과 바인딩 인자를 모아 두는 fake. DataSource 없이 동작한다(super 호출 없음) */
    private class CapturingJdbc : JdbcTemplate() {
        val queries = mutableListOf<Pair<String, List<Any?>>>()

        override fun update(sql: String, vararg args: Any?): Int {
            queries += sql to args.toList()
            return args.size
        }
    }

    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private fun row(corpCode: String, stockCode: String = "005930") = DartCorpRow(
        corpCode = corpCode, corpName = "삼성전자", stockCode = stockCode,
        modifyDate = LocalDate.of(2026, 8, 14),
    )

    @Test
    fun `SQL에 ON CONFLICT DO UPDATE가 들어간다`() {
        // dart_corp_map은 주 1회 최신값으로 덮어써야 하는 매핑이다 — DO NOTHING이면
        // 상장폐지·코드변경으로 바뀐 stock_code가 영영 반영 안 된다
        val jdbc = CapturingJdbc()

        JdbcCorpMapStore(jdbc).upsertAll(listOf(row("00126380")), now)

        val sql = jdbc.queries.single().first
        assertThat(sql).contains("INSERT INTO dart_corp_map")
        assertThat(sql).contains("ON CONFLICT (corp_code) DO UPDATE SET")
        assertThat(sql).contains("stock_code = EXCLUDED.stock_code")
    }

    @Test
    fun `5개 컬럼이 순서대로 바인딩된다`() {
        val jdbc = CapturingJdbc()

        JdbcCorpMapStore(jdbc).upsertAll(listOf(row("00126380")), now)

        assertThat(jdbc.queries.single().second).containsExactly(
            "00126380", "삼성전자", "005930", LocalDate.of(2026, 8, 14), now,
        )
    }

    @Test
    fun `여러 행이 한 번의 INSERT 문으로 묶인다`() {
        val jdbc = CapturingJdbc()

        JdbcCorpMapStore(jdbc).upsertAll(listOf(row("00126380"), row("00152880"), row("00166227")), now)

        assertThat(jdbc.queries).hasSize(1)
        assertThat(jdbc.queries.single().second).hasSize(3 * 5)
    }

    @Test
    fun `청크 경계를 넘는 행수는 여러 문으로 나뉜다`() {
        // 바인드 파라미터 상한(65,535) 때문에 청크가 필수다 — CHUNK_SIZE+1행으로 경계를 넘긴다.
        val jdbc = CapturingJdbc()
        val rows = (1..JdbcCorpMapStore.CHUNK_SIZE + 1).map { row("%08d".format(it)) }

        JdbcCorpMapStore(jdbc).upsertAll(rows, now)

        assertThat(jdbc.queries).hasSize(2)
        assertThat(jdbc.queries[0].second).hasSize(JdbcCorpMapStore.CHUNK_SIZE * 5)
        assertThat(jdbc.queries[1].second).hasSize(1 * 5)
    }

    @Test
    fun `빈 목록이면 SQL을 아예 실행하지 않는다`() {
        val jdbc = CapturingJdbc()

        JdbcCorpMapStore(jdbc).upsertAll(emptyList(), now)

        assertThat(jdbc.queries).isEmpty()
    }

    @Test
    fun `modify_date가 null이면 null로 바인딩된다`() {
        val jdbc = CapturingJdbc()

        JdbcCorpMapStore(jdbc).upsertAll(listOf(row("00126380").copy(modifyDate = null)), now)

        assertThat(jdbc.queries.single().second[3]).isNull()
    }
}
