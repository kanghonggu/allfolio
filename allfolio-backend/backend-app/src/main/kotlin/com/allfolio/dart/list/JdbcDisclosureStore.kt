package com.allfolio.dart.list

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime

/** `dart_disclosure` 한 행. 저장 직전 상태 — 정규화·판정이 끝난 값만 담는다 */
data class DisclosureInsert(
    val rceptNo: String,
    val corpCode: String,
    val corpName: String,
    val stockCode: String?,
    val corpCls: String?,
    val reportNm: String,
    val reportNmNorm: String,
    val rceptDt: LocalDate,
    val flrNm: String?,
    val rm: String?,
    val isMaterial: Boolean,
    val materialTier: Short?,
    val isCorrection: Boolean,
)

/**
 * `ON CONFLICT (rcept_no) DO NOTHING RETURNING rcept_no`로 **실제로 삽입된 행만** 돌려준다.
 * 이 반환이 곧 델타이고, `elestock` 호출(Task 11)과 피드 노출은 오직 이것만 소비한다.
 * 배치를 몇 번 재실행해도 부작용이 없는 근거가 여기다.
 *
 * **JPA로는 안 된다** — `saveAll`은 SELECT 후 INSERT/UPDATE라 "새로 들어간 행"을 구분해
 * 주지 않는다. `NavCurrencyDailyStore`(`backend-app/.../snapshot/`)가 같은 이유로
 * `JdbcTemplate`을 쓴다.
 *
 * **배치 안 중복은 사전에 접는다.** `ON CONFLICT`는 같은 문(statement) 안에서 중복된 키를
 * 막지 못한다. D-1과 D 범위가 겹쳐 같은 `rcept_no`가 두 번 오는 경우가 실제로 있다
 * (수집 서비스가 매 실행마다 D-1~D를 다시 훑는다).
 *
 * **SQL은 로컬 Postgres로 1회 수동 검증했다** — H2 2.2.224는 PostgreSQL 모드에서도
 * `ON CONFLICT`와 `RETURNING`을 둘 다 지원하지 않아(실측) 이 테스트 스위트에서는 실행 자체가
 * 안 된다. 이 클래스의 테스트는 `JdbcTemplate`을 가로채는 fake로 SQL 문자열·바인딩 인자·
 * 델타 매핑만 검증한다.
 */
@Component
class JdbcDisclosureStore(private val jdbc: JdbcTemplate) {

    fun insertIgnoringConflicts(rows: List<DisclosureInsert>, collectedAt: LocalDateTime): List<String> {
        if (rows.isEmpty()) return emptyList()
        // ON CONFLICT는 문(statement) 하나 안의 중복은 못 막는다 — 여기서 먼저 접는다.
        val deduped = rows.associateBy { it.rceptNo }.values

        return deduped.mapNotNull { r ->
            jdbc.query(
                """
                INSERT INTO dart_disclosure
                    (rcept_no, corp_code, corp_name, stock_code, corp_cls,
                     report_nm, report_nm_norm, rcept_dt, flr_nm, rm,
                     is_material, material_tier, is_correction, collected_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (rcept_no) DO NOTHING
                RETURNING rcept_no
                """.trimIndent(),
                { rs, _ -> rs.getString(1) },
                r.rceptNo, r.corpCode, r.corpName, r.stockCode, r.corpCls,
                r.reportNm, r.reportNmNorm, r.rceptDt, r.flrNm, r.rm,
                r.isMaterial, r.materialTier, r.isCorrection, collectedAt,
            ).firstOrNull()
        }
    }
}
