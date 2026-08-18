package com.allfolio.dart.list

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
 *
 * **JPA로는 안 된다** — `saveAll`은 SELECT 후 INSERT/UPDATE라 "새로 들어간 행"을 구분해
 * 주지 않는다. `NavCurrencyDailyStore`(`backend-app/.../snapshot/`)가 같은 이유로
 * `JdbcTemplate`을 쓴다.
 *
 * **트랜잭션 경계.** 청크 하나가 도중에 죽으면(원격 Neon 타임아웃 등) `@Transactional`이
 * 없을 때는 그 전 청크들이 이미 커밋돼 있다. 그 청크 안의 행은 DB에는 들어갔지만
 * 메서드가 예외로 끝나 델타로 반환되지 못하고, 다음 재실행이 같은 D-1~D 구간을 다시
 * 수집해도 `ON CONFLICT DO NOTHING`이 그 행을 조용히 걸러 **영원히 어떤 델타에도 안
 * 잡힌다** — Task 11의 `elestock` 호출이 델타로만 구동되므로 공시는 쌓이는데 임원
 * 소유변동은 영영 안 채워진다. 오류도 경고도 없어 정상 재실행과 구분되지 않는다.
 * `@Transactional`로 메서드 전체를 한 트랜잭션으로 묶으면 중간에 죽을 때 전부 롤백돼
 * "아무것도 커밋 안 됨"이 되고, 처음부터 재시도해도 안전하다.
 *
 * **행마다 왕복하지 않는다.** 청크당 다중행 `INSERT ... VALUES (…),(…),… ON CONFLICT
 * DO NOTHING RETURNING`으로 묶는다. 로컬 Postgres 실측: 9,000행 단건 INSERT 반복
 * 1.624s 대 다중행 INSERT 0.171s — 유닉스 소켓이라 지연이 거의 0인 로컬에서도 10배
 * 차이다. Neon은 원격 서버리스라 왕복당 수십 ms가 더 붙고, 설계 1절 원칙 2가 실제
 * 병목을 Neon CU-hours로 못 박고 있어 왕복 수를 줄이는 쪽이 이긴다.
 *
 * **청크 크기.** Postgres/JDBC 바인드 파라미터 상한은 65,535개이고 한 행이 14컬럼이므로
 * 한 문(statement)의 하드캡은 65535/14=4,681행이다(실측 최다일 2026-08-14 반기보고서
 * 마감 4,555건이 이 캡에 근접한다). 그 캡에 바짝 붙이지 않고 1,000행을 청크로 잡아
 * 4배 이상 여유를 둔다 — 컬럼이 하나 늘 때마다 캡을 다시 계산하지 않아도 된다.
 *
 * **배치 안 중복은 사전에 접는다.** 이유는 파라미터 낭비를 줄이는 것과, 나중에 `DO NOTHING`을
 * `DO UPDATE`로 바꾸는 날을 위한 대비다. **`ON CONFLICT ... DO NOTHING`은 같은 문 안의
 * 중복 키를 사실 허용한다** — 실측: 같은 `rcept_no` 3행을 한 `VALUES`에 넣으면 오류 없이
 * 첫 행만 반영되고 `RETURNING`은 1행이다. 이 제약이 실제로 터지는 쪽은 `DO UPDATE`다
 * (`ERROR: ON CONFLICT DO UPDATE command cannot affect row a second time`). 그래도 dedup을
 * 남겨 두는 이유는 위 두 가지 때문이지, "같은 문 안 중복을 막는다"는 잘못된 전제 때문이
 * 아니다. D-1과 D 범위가 겹쳐 같은 `rcept_no`가 두 번 오는 경우가 실제로 있다.
 *
 * **SQL은 로컬 Postgres로 수동 검증했다** — H2 2.2.224는 PostgreSQL 모드에서도
 * `ON CONFLICT`와 `RETURNING`을 둘 다 지원하지 않아(실측) 이 테스트 스위트에서는 실행 자체가
 * 안 된다. 이 클래스의 단위 테스트는 `JdbcTemplate`을 가로채는 fake로 SQL 문자열·바인딩
 * 인자·청크 분할·델타 매핑만 검증한다.
 *
 * **가짜 기반 테스트가 못 잡는 것 둘.** (1) `RowMapper`가 실제로 호출되지 않으므로
 * `rs.getString(1)`을 다른 컬럼 인덱스로 바꿔도 테스트가 초록으로 남는다. (2) SQL의
 * 컬럼 목록 순서(`stock_code, corp_cls` 등)를 바꿔도 바인딩 순서만 맞으면 테스트가 못
 * 잡는다 — 둘 다 VARCHAR라 Postgres도 조용히 받는다. 이 두 정렬은 이 테스트가 아니라
 * Task 1 마이그레이션 대조와 위 실제 Postgres 1회 검증으로 담보한다. "테스트가 초록"을
 * "컬럼 정렬이 맞다"의 증거로 여기지 말 것.
 */
@Component
class JdbcDisclosureStore(private val jdbc: JdbcTemplate) {

    companion object {
        /** 근거는 클래스 KDoc "청크 크기" 절. 상한 4,681행의 4배 이상 여유. */
        internal const val CHUNK_SIZE = 1000

        private const val COLUMNS_PER_ROW = 14
        private const val VALUES_PLACEHOLDER = "(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    }

    @Transactional
    fun insertIgnoringConflicts(rows: List<DisclosureInsert>, collectedAt: LocalDateTime): List<String> {
        if (rows.isEmpty()) return emptyList()
        val deduped = rows.associateBy { it.rceptNo }.values.toList()

        return deduped.chunked(CHUNK_SIZE).flatMap { chunk -> insertChunk(chunk, collectedAt) }
    }

    private fun insertChunk(chunk: List<DisclosureInsert>, collectedAt: LocalDateTime): List<String> {
        check(chunk.size * COLUMNS_PER_ROW <= 65_535) { "청크가 바인드 파라미터 상한을 넘는다: ${chunk.size}행" }

        val valuesSql = chunk.joinToString(",") { VALUES_PLACEHOLDER }
        val args = chunk.flatMap { r ->
            listOf(
                r.rceptNo, r.corpCode, r.corpName, r.stockCode, r.corpCls,
                r.reportNm, r.reportNmNorm, r.rceptDt, r.flrNm, r.rm,
                r.isMaterial, r.materialTier, r.isCorrection, collectedAt,
            )
        }

        return jdbc.query(
            """
            INSERT INTO dart_disclosure
                (rcept_no, corp_code, corp_name, stock_code, corp_cls,
                 report_nm, report_nm_norm, rcept_dt, flr_nm, rm,
                 is_material, material_tier, is_correction, collected_at)
            VALUES $valuesSql
            ON CONFLICT (rcept_no) DO NOTHING
            RETURNING rcept_no
            """.trimIndent(),
            { rs, _ -> rs.getString(1) },
            *args.toTypedArray(),
        )
    }
}
