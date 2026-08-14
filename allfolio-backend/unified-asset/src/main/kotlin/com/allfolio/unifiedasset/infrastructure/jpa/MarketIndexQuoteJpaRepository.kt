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

    /**
     * 여러 지수의 가장 최근 한 건씩을 **쿼리 한 번으로** 준다. 시장 화면(AF-104)이 쓴다.
     *
     * 최신 판정 규칙은 [findLatest]와 같고 **이 JPQL 안에만 있다.** 코틀린에서 정렬해 고르면
     * 같은 규칙이 두 벌이 되고, 한쪽만 고쳐지는 순간 같은 날 시가가 종가보다 최신으로 잡힌다.
     * 슬롯을 문자열로 비교하면 안 되는 이유는 [findLatest] 참조.
     *
     * **`DISTINCT ON`이나 윈도 함수를 쓰지 말 것** — 이 리포지터리 테스트는 H2에서 돈다.
     * 벤더 전용 문법으로 바꾸면 슬롯 순서 검증이 통째로 테스트에서 빠진다.
     * 그래서 이식 가능한 상관 서브쿼리 `NOT EXISTS`("나보다 최신인 행이 없다")로 짰다.
     *
     * 수집된 적 없는 코드는 결과에 **그냥 없다.** 호출부가 코드로 매핑해 빠진 것을 가려내야 한다.
     */
    @Query(
        """
        SELECT q FROM MarketIndexQuoteEntity q
        WHERE q.indexCode IN :indexCodes
          AND NOT EXISTS (
            SELECT 1 FROM MarketIndexQuoteEntity o
            WHERE o.indexCode = q.indexCode
              AND (o.tradeDate > q.tradeDate
                OR (o.tradeDate = q.tradeDate
                    AND CASE o.slot WHEN 'CLOSE' THEN 3 WHEN 'MID' THEN 2 ELSE 1 END
                      > CASE q.slot WHEN 'CLOSE' THEN 3 WHEN 'MID' THEN 2 ELSE 1 END))
          )
        """,
    )
    fun findLatestByCodes(@Param("indexCodes") indexCodes: Collection<String>): List<MarketIndexQuoteEntity>

    /** 수집 시 덮어쓸 대상을 가려낸다 */
    fun findByIndexCodeAndTradeDateAndSlot(
        indexCode: String,
        tradeDate: LocalDate,
        slot: String,
    ): MarketIndexQuoteEntity?
}
