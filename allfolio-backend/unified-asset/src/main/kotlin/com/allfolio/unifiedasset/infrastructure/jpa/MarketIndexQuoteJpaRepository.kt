package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface MarketIndexQuoteJpaRepository : JpaRepository<MarketIndexQuoteEntity, UUID> {

    /**
     * 그 지수의 가장 최근 한 건.
     *
     * **슬롯을 문자열로 정렬하면 안 된다** — 사전순은 CLOSE < MID < OPEN이라
     * 같은 날 OPEN이 종가보다 최신으로 잡힌다. CASE로 시간 순서를 명시한다.
     */
    @Query(
        """
        SELECT q FROM MarketIndexQuoteEntity q
        WHERE q.indexCode = :indexCode
        ORDER BY q.tradeDate DESC,
                 CASE q.slot WHEN 'CLOSE' THEN 3 WHEN 'MID' THEN 2 ELSE 1 END DESC
        LIMIT 1
        """,
    )
    fun findLatest(@Param("indexCode") indexCode: String): MarketIndexQuoteEntity?

    /** 수집 시 덮어쓸 대상을 가려낸다 */
    fun findByIndexCodeAndTradeDateAndSlot(
        indexCode: String,
        tradeDate: LocalDate,
        slot: String,
    ): MarketIndexQuoteEntity?
}
