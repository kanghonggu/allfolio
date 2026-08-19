package com.allfolio.realasset

import com.allfolio.unifiedasset.infrastructure.entity.RealAssetValuationEntity
import com.allfolio.unifiedasset.infrastructure.jpa.RealAssetJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.RealAssetValuationJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * JPA 레포를 [RealAssetValuationService.Store]에 맞춘다. `JpaCommodityStore`와 같은 배치다.
 *
 * 여기가 **엔티티와 도메인이 만나는 유일한 자리**다. 서비스와 평가 어댑터는 JPA를 모른다.
 *
 * **`assetType` 문자열을 enum으로 바꾸는 것도 여기서 한다.** DB에는 문자열이 들어 있고
 * enum에 없는 값이 있을 수 있다(수기 수정·구버전 데이터). 그때 배치 전체를 죽이지 않고
 * **그 자산만 빼고 로그를 남긴다** — 활성 자산 목록을 못 읽으면 그날 스냅샷이 통째로 비는데,
 * 원인이 자산 한 건이라면 나머지는 평가되는 편이 낫다.
 */
@Component
class JpaRealAssetValuationStore(
    private val assets: RealAssetJpaRepository,
    private val valuations: RealAssetValuationJpaRepository,
) : RealAssetValuationService.Store {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun activeAssets(): List<RealAsset> =
        assets.findByIsActiveTrue().mapNotNull { entity ->
            val type = AssetType.entries.firstOrNull { it.name == entity.assetType }
            if (type == null) {
                log.warn("[실물자산] 알 수 없는 asset_type={} 자산 id={} — 평가에서 제외", entity.assetType, entity.id)
                return@mapNotNull null
            }
            RealAsset(
                id = entity.id,
                assetType = type,
                sourceRef = entity.sourceRef,
                quantity = entity.quantity,
                purity = entity.purity,
            )
        }

    override fun existingAssetIds(valuedOn: LocalDate): Set<UUID> =
        valuations.findByValuedOn(valuedOn).map { it.realAssetId }.toSet()

    /**
     * **기존 행을 조회해 덮는다 — 새 행을 만들지 않는다.** `uq_valuation`이 막아 주긴 하지만,
     * 그건 예외로 끝나는 것이지 갱신이 아니다. 워크플로의 `--retry`가 이 경로에 기대어
     * 멱등해진다(원자재 수집이 겹쳐 돌다 유니크 제약에 걸려 배치 전체를 날린 전례가 있다).
     */
    override fun save(snapshots: List<ValuationSnapshot>, now: Instant) {
        if (snapshots.isEmpty()) return

        val valuedOn = snapshots.first().valuedOn
        val existing = valuations.findByValuedOn(valuedOn).associateBy { it.realAssetId }

        val entities = snapshots.map { snapshot ->
            existing[snapshot.realAssetId]?.apply {
                unitPrice = snapshot.unitPrice
                priceUnit = snapshot.priceUnit
                valuationKrw = snapshot.valuationKrw
                priceAsOf = snapshot.priceAsOf
                stalenessDays = snapshot.stalenessDays.toShort()
                priceBasis = snapshot.priceBasis.name
                confidence = snapshot.confidence?.name
                createdAt = now
            } ?: RealAssetValuationEntity(
                id = UUID.randomUUID(),
                realAssetId = snapshot.realAssetId,
                valuedOn = snapshot.valuedOn,
                unitPrice = snapshot.unitPrice,
                priceUnit = snapshot.priceUnit,
                valuationKrw = snapshot.valuationKrw,
                priceAsOf = snapshot.priceAsOf,
                stalenessDays = snapshot.stalenessDays.toShort(),
                priceBasis = snapshot.priceBasis.name,
                confidence = snapshot.confidence?.name,
                createdAt = now,
            )
        }

        valuations.saveAll(entities)
    }
}
