package com.allfolio.dart.list

import com.allfolio.dart.DartReclassifyService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * S13 재분류용 읽기·갱신. [JdbcDisclosureStore]와 같은 표를 보지만 목적이 달라 분리했다 —
 * 그쪽은 수집 경로(INSERT 전용)이고 이쪽은 일회성 정정이다.
 *
 * **`rcept_no` 커서로 페이지를 넘긴다.** OFFSET을 쓰면 갱신이 정렬에 영향을 주지 않더라도
 * 표가 커질수록 매 페이지가 앞부분을 다시 읽는다. `rcept_no`는 PK라 인덱스가 이미 있다.
 */
@Component
class JdbcDisclosureReclassifyStore(
    private val jdbc: JdbcTemplate,
) : DartReclassifyService.Store {

    override fun page(after: String?, limit: Int): List<DartReclassifyService.Row> {
        val sql = buildString {
            append("SELECT rcept_no, report_nm, material_tier FROM dart_disclosure ")
            if (after != null) append("WHERE rcept_no > ? ")
            append("ORDER BY rcept_no LIMIT ?")
        }
        val args: Array<Any> = if (after != null) arrayOf(after, limit) else arrayOf(limit)

        return jdbc.query(sql, { rs, _ ->
            // material_tier는 nullable이다. getShort()는 NULL을 0으로 돌려주므로
            // wasNull()로 갈라야 한다 — 안 그러면 "미해당"이 전부 Tier 0으로 읽혀
            // 모든 행이 바뀐 것처럼 보인다.
            val tier = rs.getShort("material_tier").let { if (rs.wasNull()) null else it }
            DartReclassifyService.Row(
                rceptNo = rs.getString("rcept_no"),
                reportNm = rs.getString("report_nm"),
                materialTier = tier,
            )
        }, *args)
    }

    /**
     * 한 문으로 묶는다. `VALUES` 목록을 조인해 PK로 갱신한다 — 행마다 왕복하면 Neon 원격
     * 지연이 행 수만큼 곱해진다([JdbcDisclosureStore]의 "행마다 왕복하지 않는다"와 같은 근거).
     */
    @Transactional
    override fun update(rows: List<DartReclassifyService.Update>): Int {
        if (rows.isEmpty()) return 0

        val values = rows.joinToString(",") { "(?,?::smallint,?)" }
        val sql = """
            UPDATE dart_disclosure d
               SET material_tier = v.tier, is_material = v.is_material
              FROM (VALUES $values) AS v(rcept_no, tier, is_material)
             WHERE d.rcept_no = v.rcept_no
        """.trimIndent()

        val args = rows.flatMap { listOf(it.rceptNo, it.materialTier, it.isMaterial) }
        return jdbc.update(sql, *args.toTypedArray())
    }
}
