package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.infrastructure.entity.AssetEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface AssetJpaRepository : JpaRepository<AssetEntity, UUID> {
    fun findByUserId(userId: UUID): List<AssetEntity>
    fun findByAccountId(accountId: UUID): List<AssetEntity>

    /**
     * 유형으로 전 사용자 자산을 읽는다. 자동 평가 배치(A1)가 쓴다 — 배치는 사용자별로
     * 나뉘지 않으므로 `user_id` 조건이 **없는 것이 맞다.**
     *
     * **조회 API에 이 메서드를 쓰지 말 것.** 사용자 경계가 없어서 그대로 쓰면 남의 자산이
     * 나간다 — 사용자 대상 조회는 `findByUserId`를 쓴다.
     */
    fun findByTypeIn(types: Collection<AssetType>): List<AssetEntity>

    @Modifying
    @Query("DELETE FROM AssetEntity a WHERE a.accountId = :accountId")
    fun deleteByAccountId(accountId: UUID)
}
