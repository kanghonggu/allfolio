package com.allfolio.dart.corp

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * @param fetched `corpCode.xml`에서 `corp_code`가 있는 행 전체 수(상장 여부 무관, 실측 118,712)
 * @param listed 그중 `stock_code`가 있어 실제로 `dart_corp_map`에 적재된 행 수(실측 3,983, 3.4%).
 *   **이 값이 곧 이번 갱신의 적재 건수다** — `fetched`는 참고용 스캔 총량일 뿐 DB에 안 들어간다.
 */
data class CorpMapSummary(val fetched: Int, val listed: Int)

/**
 * `dart_corp_map` 갱신. 주 1회면 충분하다 — 근거는 [DartCorpCodeClient] KDoc.
 *
 * **상장사만 적재한다.** `client.fetch()`가 돌려주는 [DartCorpParseResult.listedRows]는 이미
 * `stock_code`가 있는 행만 걸러(파싱 중 필터링, 근거는 [DartCorpCodeClient] KDoc) 온 것이므로
 * 여기서는 그 결과를 그대로 적재하기만 한다.
 *
 * **`corp_code` 단위 upsert이지, 테이블을 비우고 다시 채우는 진짜 "덮어쓰기"가 아니다.** 이번
 * 응답에 있는 `corp_code`만 갱신되고, DART 마스터에서 통째로 빠진(상장폐지 후 완전히 사라진)
 * `corp_code`는 지워지지 않고 `dart_corp_map`에 그대로 남는다. **삭제 스윕은 일부러 안 만든다**
 * — 이 테이블은 이 계획 안에서 아무도 안 읽으므로 스윕 로직에 들이는 복잡도가 정당화되지
 * 않는다. 남은 stale 행은 `updated_at`이 오래된 것으로 나중에 찾아낼 수 있다는 정도가
 * 완화책이다.
 *
 * **쓰기는 [JdbcCorpMapStore](JdbcTemplate 청크 upsert)로 한다 — JPA `save()`가 아니다.** 이유는
 * [JdbcCorpMapStore] KDoc에 있다.
 */
@Service
class DartCorpMapService(
    private val client: DartCorpCodeClient,
    private val store: JdbcCorpMapStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun refresh(now: LocalDateTime): CorpMapSummary {
        val result = client.fetch()
        store.upsertAll(result.listedRows, now)

        log.info("[DART] corp_map 갱신 total={} listed={}", result.totalRows, result.listedRows.size)
        return CorpMapSummary(fetched = result.totalRows, listed = result.listedRows.size)
    }
}
