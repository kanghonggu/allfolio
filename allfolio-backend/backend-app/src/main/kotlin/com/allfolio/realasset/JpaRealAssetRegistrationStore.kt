package com.allfolio.realasset

import com.allfolio.unifiedasset.infrastructure.entity.RealAssetEntity
import com.allfolio.unifiedasset.infrastructure.jpa.RealAssetJpaRepository
import org.springframework.stereotype.Component

/** JPA 레포를 [RealAssetRegistrationService.Store]에 맞춘다. `JpaCommodityStore`와 같은 배치다 */
@Component
class JpaRealAssetRegistrationStore(
    private val repository: RealAssetJpaRepository,
) : RealAssetRegistrationService.Store {

    override fun insert(asset: NewRealAsset) {
        repository.save(
            RealAssetEntity(
                id = asset.id,
                userId = asset.userId,
                assetType = asset.assetType.name,
                subType = asset.subType,
                name = asset.name,
                sourceRef = asset.sourceRef,
                quantity = asset.quantity,
                purity = asset.purity,
                acquiredAt = asset.acquiredAt,
                acquiredCostKrw = asset.acquiredCostKrw,
                includeInTwr = asset.includeInTwr,
                isActive = asset.isActive,
                createdAt = asset.createdAt,
                // 등록 시점에는 생성·수정 시각이 같다. 수정 API가 생기면 그쪽이 이 값만 바꾼다
                updatedAt = asset.createdAt,
            ),
        )
    }
}
