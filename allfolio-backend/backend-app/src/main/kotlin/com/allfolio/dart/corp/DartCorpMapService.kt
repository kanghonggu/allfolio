package com.allfolio.dart.corp

import com.allfolio.unifiedasset.infrastructure.entity.DartCorpMapEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartCorpMapJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** @param fetched corpCode.xml에서 읽은 전체 행 수 @param listed 그중 stock_code가 있는(상장) 행 수 */
data class CorpMapSummary(val fetched: Int, val listed: Int)

/**
 * `dart_corp_map` 전량 갱신. 주 1회면 충분하다 — 근거는 `DartCorpCodeClient` KDoc.
 *
 * **전량 갱신이다. upsert가 아니라 덮어쓰기다.** 상장/폐지·코드 변경이 나면 같은 `corp_code`의
 * `stock_code`가 이번 주와 지난 주가 다를 수 있다. `DartCorpMapEntity`는 필드가 전부 `var`라
 * `save()`(내부적으로 `corp_code` PK로 merge)가 이번 응답 값으로 통째로 덮어써, 옛 값이 부분적으로
 * 남는 일이 없다.
 */
@Service
class DartCorpMapService(
    private val client: DartCorpCodeClient,
    private val repository: DartCorpMapJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun refresh(now: LocalDateTime): CorpMapSummary {
        val rows = client.fetch()

        val entities = rows.map { row ->
            DartCorpMapEntity(
                corpCode = row.corpCode,
                corpName = row.corpName,
                stockCode = row.stockCode,
                modifyDate = row.modifyDate,
                updatedAt = now,
            )
        }
        repository.saveAll(entities)

        val listed = rows.count { it.stockCode != null }
        log.info("[DART] corp_map 갱신 total={} listed={}", rows.size, listed)
        return CorpMapSummary(fetched = rows.size, listed = listed)
    }
}
