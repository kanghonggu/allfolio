package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.RtmsDealCacheEntity
import com.allfolio.unifiedasset.infrastructure.entity.RtmsFetchLogEntity
import com.allfolio.unifiedasset.infrastructure.entity.RtmsFetchLogId
import org.springframework.data.jpa.repository.JpaRepository
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
}

interface RtmsFetchLogJpaRepository : JpaRepository<RtmsFetchLogEntity, RtmsFetchLogId>
