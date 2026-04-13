package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetType
import java.util.UUID

interface AssetRepository {
    fun save(asset: Asset): Asset
    fun saveAll(assets: List<Asset>): List<Asset>
    fun findById(id: UUID): Asset?
    fun findByUserId(userId: UUID): List<Asset>
    fun findByAccountId(accountId: UUID): List<Asset>
    fun deleteByAccountId(accountId: UUID)
    fun delete(id: UUID)
}
