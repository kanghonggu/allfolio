package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.AssetEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface AssetJpaRepository : JpaRepository<AssetEntity, UUID> {
    fun findByUserId(userId: UUID): List<AssetEntity>
    fun findByAccountId(accountId: UUID): List<AssetEntity>

    @Modifying
    @Query("DELETE FROM AssetEntity a WHERE a.accountId = :accountId")
    fun deleteByAccountId(accountId: UUID)
}
