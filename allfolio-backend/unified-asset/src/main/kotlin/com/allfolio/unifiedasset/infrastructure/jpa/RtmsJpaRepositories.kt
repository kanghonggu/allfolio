package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.RtmsDealCacheEntity
import com.allfolio.unifiedasset.infrastructure.entity.RtmsFetchLogEntity
import com.allfolio.unifiedasset.infrastructure.entity.RtmsFetchLogId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

interface RtmsDealCacheJpaRepository : JpaRepository<RtmsDealCacheEntity, UUID> {

    /**
     * 자연키로 기존 행을 찾는다. **upsert 경로다** — 해제 상태가 나중에 바뀌므로
     * 있으면 덮고 없으면 넣는다.
     */
    fun findByAptSeqAndExclusiveAreaM2AndDealDateAndFloorAndDealAmountKrw(
        aptSeq: String,
        exclusiveAreaM2: BigDecimal,
        dealDate: LocalDate,
        floor: Int,
        dealAmountKrw: Long,
    ): RtmsDealCacheEntity?

    /**
     * 평가용 조회 — `(단지, 전용면적)`의 기간 내 거래.
     *
     * **해제 거래를 여기서 거르지 않는다.** 조회자가 `isCancelled`를 보고 판단한다 —
     * 몇 건이 해제였는지가 표본 신뢰도의 근거가 되기 때문이다(시계에서 `listingCount`를
     * 함께 낸 것과 같은 판단이다).
     *
     * `idx_rtms_deals_apt_area_date`가 그대로 받는다.
     */
    fun findByAptSeqAndExclusiveAreaM2AndDealDateBetween(
        aptSeq: String,
        exclusiveAreaM2: BigDecimal,
        from: LocalDate,
        to: LocalDate,
    ): List<RtmsDealCacheEntity>

    /**
     * 단지·평형 목록 (R2 선택 UI).
     *
     * **`(단지, 면적)`으로 묶어 거래 수를 함께 낸다.** 거래가 실측 분기 2건꼴이라, 사용자가
     * 고른 평형에 표본이 몇 건인지 화면이 말해 줄 수 있어야 한다.
     *
     * **해제 거래를 뺀다.** 성사되지 않은 거래만 있는 평형을 보여 주면 사용자가 그걸 고르고
     * 평가가 영원히 null이 된다.
     *
     * 단지명은 `MAX`로 하나 고른다 — 같은 `apt_seq`인데 표기가 미세하게 다른 행이 섞일 수
     * 있고(재건축·명칭 변경), 그 경우 어느 쪽이든 사용자가 알아본다. 매칭은 이름이 아니라
     * `apt_seq`가 지므로 여기서 이름이 흔들려도 값은 안 틀린다.
     */
    @Query(
        """
        SELECT d.aptSeq            AS aptSeq,
               MAX(d.aptName)      AS aptName,
               MAX(d.umdName)      AS umdName,
               MAX(d.buildYear)    AS buildYear,
               d.exclusiveAreaM2   AS exclusiveAreaM2,
               COUNT(d)            AS dealCount
        FROM RtmsDealCacheEntity d
        WHERE d.sggCode = :sggCode
          AND d.isCancelled = false
          AND (:query IS NULL OR LOWER(d.aptName) LIKE LOWER(CONCAT('%', :query, '%')))
        GROUP BY d.aptSeq, d.exclusiveAreaM2
        """,
    )
    fun findComplexRows(
        @Param("sggCode") sggCode: String,
        @Param("query") query: String?,
    ): List<ComplexRow>
}

interface RtmsFetchLogJpaRepository : JpaRepository<RtmsFetchLogEntity, RtmsFetchLogId>

/** [RtmsDealCacheJpaRepository.findComplexRows]의 투영 */
interface ComplexRow {
    val aptSeq: String
    val aptName: String
    val umdName: String
    val buildYear: Int?
    val exclusiveAreaM2: BigDecimal
    val dealCount: Int
}
