package com.allfolio.dart.corp

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * `dart_corp_map` 청크 upsert.
 *
 * **JPA `save()`가 아니라 `JdbcTemplate`을 쓰는 이유.** `DartCorpMapEntity`의 `@Id corpCode`는
 * 생성 시점에 항상 non-null로 채워지므로 Spring Data의 `isNew()` 판정이 늘 false가 되고,
 * `save()`는 `persist`가 아니라 `merge`를 호출한다. `merge`는 행마다 먼저 존재 확인 SELECT를
 * 날리고, 그 SELECT는 `hibernate.jdbc.batch_size`(`application.yml`, 500)가 적용되는 대상이
 * 아니다 — 그 설정은 그 뒤에 나가는 INSERT/UPDATE에만 적용된다. 즉 상장사 3,983행을
 * `saveAll()`로 적재하면 SELECT 왕복이 3,983번 난다. Neon은 설계 1절 원칙 2가 문서화한
 * 병목이라 이 왕복을 통째로 없앤다. (`DartCorpMapEntity`·`DartCorpMapJpaRepository`는 지우지
 * 않았다 — 향후 `corp_code` 단건 조회(read) 경로가 생기면 그건 JPA 그대로 쓸 수 있다. 쓰기만
 * 이쪽으로 옮긴다.)
 *
 * `DartDisclosureCollectService`(Task 8)의 `JdbcDisclosureStore`와 같은 패턴이다 — 다만 그쪽은
 * `ON CONFLICT DO NOTHING`(델타만 필요)이고 여기는 **`ON CONFLICT DO UPDATE`**다:
 * `dart_corp_map`은 주 1회 최신값으로 갱신하는 매핑이라, 이미 있는 `corp_code`도 이번 응답
 * 값으로 덮어써야 한다(상장폐지·코드변경으로 `stock_code`가 바뀔 수 있다).
 *
 * **청크 크기.** 5컬럼(`corp_code, corp_name, stock_code, modify_date, updated_at`) 기준
 * 바인드 파라미터 상한 65,535 ÷ 5 = 13,107행이 한 문(statement)의 하드캡이다. 실측 상장사 수
 * (3,983)는 이미 한 청크에 다 들어가 지금은 사실상 단일 statement로 끝난다 — 그래도 상장사
 * 수가 배로 늘어도 여유가 크게 남도록 2,000으로 잡는다(캡의 6.5배 여유).
 *
 * **트랜잭션 경계는 여기서 걸지 않는다.** `DartCorpMapService.refresh()`의 `@Transactional`이
 * 메서드 전체를 묶는다 — 청크 하나가 중간에 죽으면 이번 주 갱신이 반쯤만 반영된 채로 다음 주
 * 재실행을 맞는 일이 없도록, 실패 시 전부 롤백돼야 한다.
 */
@Component
class JdbcCorpMapStore(private val jdbc: JdbcTemplate) {

    companion object {
        /** 근거는 클래스 KDoc "청크 크기" 절 */
        internal const val CHUNK_SIZE = 2000

        private const val COLUMNS_PER_ROW = 5
        private const val VALUES_PLACEHOLDER = "(?,?,?,?,?)"
    }

    fun upsertAll(rows: List<DartCorpRow>, updatedAt: LocalDateTime) {
        if (rows.isEmpty()) return
        rows.chunked(CHUNK_SIZE).forEach { chunk -> upsertChunk(chunk, updatedAt) }
    }

    private fun upsertChunk(chunk: List<DartCorpRow>, updatedAt: LocalDateTime) {
        check(chunk.size * COLUMNS_PER_ROW <= 65_535) { "청크가 바인드 파라미터 상한을 넘는다: ${chunk.size}행" }

        val valuesSql = chunk.joinToString(",") { VALUES_PLACEHOLDER }
        val args = chunk.flatMap { r -> listOf(r.corpCode, r.corpName, r.stockCode, r.modifyDate, updatedAt) }

        jdbc.update(
            """
            INSERT INTO dart_corp_map (corp_code, corp_name, stock_code, modify_date, updated_at)
            VALUES $valuesSql
            ON CONFLICT (corp_code) DO UPDATE SET
                corp_name = EXCLUDED.corp_name,
                stock_code = EXCLUDED.stock_code,
                modify_date = EXCLUDED.modify_date,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            *args.toTypedArray(),
        )
    }
}
