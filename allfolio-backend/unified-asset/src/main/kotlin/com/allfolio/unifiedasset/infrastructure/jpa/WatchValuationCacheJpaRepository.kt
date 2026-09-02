package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.WatchValuationCacheEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface WatchValuationCacheJpaRepository : JpaRepository<WatchValuationCacheEntity, UUID> {

    /** upsert 경로. 같은 날 배치를 두 번 돌려도 행이 늘지 않는다 */
    fun findByRefKeyAndAsOf(refKey: String, asOf: LocalDate): WatchValuationCacheEntity?

    /**
     * 평가용 폴백 조회 — `asOf` 이하 가장 최근 한 건.
     *
     * **날짜 하한을 걸지 않는다.** 금 시세와 같은 판단이다(설계 3절) — 수집이 며칠 빠져도
     * 직전 값으로 평가하고, 얼마나 낡았는지는 `staleness`로 화면이 말한다. "직전 1일"로
     * 좁히면 배치가 하루만 실패해도 평가가 통째로 사라진다.
     *
     * `uk_watch_valuation_cache (ref_key, as_of)`가 그대로 받는다 — Postgres가 btree를
     * 역방향으로도 비용 없이 스캔하므로 **별도 인덱스를 만들지 말 것.**
     */
    fun findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc(
        refKey: String,
        asOf: LocalDate,
    ): WatchValuationCacheEntity?
}
