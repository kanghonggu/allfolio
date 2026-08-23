package com.allfolio.market.realestate

import com.allfolio.unifiedasset.infrastructure.entity.RtmsDealCacheEntity
import com.allfolio.unifiedasset.infrastructure.entity.RtmsFetchLogEntity
import com.allfolio.unifiedasset.infrastructure.entity.RtmsFetchLogId
import com.allfolio.unifiedasset.infrastructure.jpa.RtmsDealCacheJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.RtmsFetchLogJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID

/**
 * 실거래가 캐시 JPA 구현.
 *
 * **덮어쓰기가 이 클래스의 존재 이유다.** 같은 거래가 처음엔 정상으로 왔다가 나중에 해제로
 * 바뀐다(실측 2.6%). `saveAll`로 새 엔티티를 밀어 넣으면 자연키 유니크 제약에 걸리거나
 * 중복 행이 생기므로, 자연키로 찾아 있으면 덮는다.
 */
@Component
class JpaRtmsDealStore(
    private val deals: RtmsDealCacheJpaRepository,
    private val fetchLog: RtmsFetchLogJpaRepository,
) : RtmsDealStore {

    @Transactional
    override fun upsertAll(deals: List<RtmsDeal>, collectedAt: LocalDateTime): Int {
        var inserted = 0
        for (d in deals) {
            val existing = this.deals
                .findByAptSeqAndExclusiveAreaM2AndDealDateAndFloorAndDealAmountKrw(
                    d.aptSeq, d.exclusiveAreaM2, d.dealDate, d.floor, d.dealAmountKrw,
                )
            if (existing == null) {
                this.deals.save(toEntity(d, collectedAt))
                inserted++
            } else {
                // 해제 상태가 바뀌는 것이 주된 갱신이다. 단지명·건축년도도 함께 덮는다 —
                // 첫 수집 당시 값이 굳으면 값을 설명하려고 들여다볼 필드가 거짓말을 한다.
                existing.aptName = d.aptName
                existing.buildYear = d.buildYear
                existing.sggCode = d.sggCode
                existing.umdName = d.umdName
                existing.isCancelled = d.cancelled
                existing.cancelledOn = d.cancelledOn
                existing.collectedAt = collectedAt
                this.deals.save(existing)
            }
        }
        return inserted
    }

    override fun findFetch(sggCode: String, month: YearMonth): RtmsFetchRecord? =
        fetchLog.findById(RtmsFetchLogId(sggCode, ym(month))).orElse(null)?.let {
            RtmsFetchRecord(it.sggCode, month, it.dealCount, it.apiCalls, it.fetchedAt)
        }

    @Transactional
    override fun recordFetch(record: RtmsFetchRecord) {
        val id = RtmsFetchLogId(record.sggCode, ym(record.month))
        val entity = fetchLog.findById(id).orElse(null)
        if (entity == null) {
            fetchLog.save(
                RtmsFetchLogEntity(
                    sggCode = record.sggCode, dealYm = ym(record.month),
                    dealCount = record.dealCount, apiCalls = record.apiCalls,
                    fetchedAt = record.fetchedAt,
                ),
            )
        } else {
            entity.dealCount = record.dealCount
            entity.apiCalls = record.apiCalls
            entity.fetchedAt = record.fetchedAt
            fetchLog.save(entity)
        }
    }

    private fun toEntity(d: RtmsDeal, collectedAt: LocalDateTime) = RtmsDealCacheEntity(
        id = UUID.randomUUID(),
        aptSeq = d.aptSeq,
        exclusiveAreaM2 = d.exclusiveAreaM2,
        dealDate = d.dealDate,
        floor = d.floor,
        dealAmountKrw = d.dealAmountKrw,
        aptName = d.aptName,
        buildYear = d.buildYear,
        sggCode = d.sggCode,
        umdName = d.umdName,
        isCancelled = d.cancelled,
        cancelledOn = d.cancelledOn,
        collectedAt = collectedAt,
    )

    /** `yyyyMM`. `YearMonth.toString()`은 `2026-07`이라 그대로 쓰면 안 된다 */
    private fun ym(m: YearMonth) = "%04d%02d".format(m.year, m.monthValue)
}
