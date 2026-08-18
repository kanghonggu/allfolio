package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.RealAssetEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RealAssetJpaRepository : JpaRepository<RealAssetEntity, UUID> {

    /**
     * 평가 배치(G5)가 도는 대상. **전 사용자다** — 배치는 사용자별로 나뉘지 않는다.
     *
     * `is_active`가 빠지면 비활성 자산까지 매일 스냅샷이 쌓인다. 오류는 안 나고 행만 는다 —
     * 사용자가 "판 금"을 계속 평가받는 셈이라 순자산이 안 줄어드는 증상으로 나온다.
     * 부분 인덱스 `idx_real_asset_user ... WHERE is_active`가 이 조건을 전제로 만들어져 있다.
     */
    fun findByIsActiveTrue(): List<RealAssetEntity>

    /** 조회 API(G7)용. 남의 자산을 주지 않으려면 user_id가 반드시 조건에 있어야 한다 */
    fun findByUserIdAndIsActiveTrue(userId: UUID): List<RealAssetEntity>
}
