package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.RealAssetValuationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface RealAssetValuationJpaRepository : JpaRepository<RealAssetValuationEntity, UUID> {

    /**
     * 그 날짜에 이미 있는 스냅샷 전부. 배치가 삽입/갱신을 가르고 기존 행을 덮는 데 쓴다.
     *
     * **날짜 하나를 통째로 읽는다** — 자산마다 따로 조회하면 자산 수만큼 왕복이 된다(Neon은 원격).
     * `CommodityCollectService`가 구간을 통째로 읽는 것과 같은 이유다.
     */
    fun findByValuedOn(valuedOn: LocalDate): List<RealAssetValuationEntity>

    /**
     * 자산별 최신 스냅샷 한 건씩. 조회 API(G7)가 쓴다.
     *
     * **`DISTINCT ON`이나 윈도 함수를 쓰지 말 것** — 이 리포지터리 테스트는 H2에서 돈다.
     * 벤더 전용 문법으로 바꾸면 최신 판정 검증이 통째로 테스트에서 빠진다.
     * `MarketCommodityQuoteJpaRepository.findLatestByCodes`와 같은 자리·같은 이유로
     * 이식 가능한 상관 서브쿼리(`NOT EXISTS` = "나보다 최신인 행이 없다")로 짰다.
     */
    @Query(
        """
        SELECT v FROM RealAssetValuationEntity v
        WHERE v.realAssetId IN :assetIds
          AND NOT EXISTS (
            SELECT 1 FROM RealAssetValuationEntity o
            WHERE o.realAssetId = v.realAssetId AND o.valuedOn > v.valuedOn
          )
        """,
    )
    fun findLatestByAssetIds(@Param("assetIds") assetIds: Collection<UUID>): List<RealAssetValuationEntity>
}
