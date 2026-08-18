package com.allfolio.realasset

import com.allfolio.unifiedasset.infrastructure.jpa.RealAssetJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.RealAssetValuationJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * JPA 레포를 [RealAssetQueryService.Store]에 맞춘다.
 *
 * **`assetType`·`priceBasis`·`confidence` 문자열을 enum으로 바꾸는 것도 여기서 한다.**
 * enum에 없는 값이면 그 자산(또는 그 스냅샷)만 빼고 로그를 남긴다 — 목록 전체가 500이 되는
 * 것보다 낫다. `JpaRealAssetValuationStore`가 같은 판단을 한다.
 */
@Component
class JpaRealAssetQueryStore(
    private val assets: RealAssetJpaRepository,
    private val valuations: RealAssetValuationJpaRepository,
) : RealAssetQueryService.Store {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun holdings(userId: UUID): List<RealAssetHolding> =
        assets.findByUserIdAndIsActiveTrue(userId).mapNotNull { entity ->
            val type = AssetType.entries.firstOrNull { it.name == entity.assetType }
            if (type == null) {
                log.warn("[실물자산] 알 수 없는 asset_type={} 자산 id={} — 조회에서 제외", entity.assetType, entity.id)
                return@mapNotNull null
            }
            RealAssetHolding(
                id = entity.id,
                assetType = type,
                subType = entity.subType,
                name = entity.name,
                quantity = entity.quantity,
                purity = entity.purity,
                acquiredAt = entity.acquiredAt,
                acquiredCostKrw = entity.acquiredCostKrw,
            )
        }

    override fun latestValuations(assetIds: Collection<UUID>): Map<UUID, LatestValuation> =
        valuations.findLatestByAssetIds(assetIds).mapNotNull { entity ->
            val basis = PriceBasis.entries.firstOrNull { it.name == entity.priceBasis }
            if (basis == null) {
                log.warn(
                    "[실물자산] 알 수 없는 price_basis={} 스냅샷 id={} — 조회에서 제외",
                    entity.priceBasis,
                    entity.id,
                )
                return@mapNotNull null
            }
            entity.realAssetId to LatestValuation(
                unitPrice = entity.unitPrice,
                priceUnit = entity.priceUnit,
                valuationKrw = entity.valuationKrw,
                valuedOn = entity.valuedOn,
                priceAsOf = entity.priceAsOf,
                stalenessDays = entity.stalenessDays.toInt(),
                priceBasis = basis,
                // confidence는 nullable이라 없어도 정상이다. 값이 있는데 enum에 없으면
                // 그것만 null로 둔다 — 신뢰도 하나 때문에 평가액을 버릴 이유가 없다.
                confidence = Confidence.entries.firstOrNull { it.name == entity.confidence },
            )
        }.toMap()
}
