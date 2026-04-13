package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.infrastructure.entity.AssetEntity
import com.allfolio.unifiedasset.infrastructure.jpa.AssetJpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class AssetRepositoryImpl(private val jpa: AssetJpaRepository) : AssetRepository {
    override fun save(asset: Asset): Asset =
        jpa.save(AssetEntity.fromDomain(asset)).toDomain()

    override fun saveAll(assets: List<Asset>): List<Asset> =
        jpa.saveAll(assets.map { AssetEntity.fromDomain(it) }).map { it.toDomain() }

    override fun findById(id: UUID): Asset? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun findByUserId(userId: UUID): List<Asset> =
        jpa.findByUserId(userId).map { it.toDomain() }

    override fun findByAccountId(accountId: UUID): List<Asset> =
        jpa.findByAccountId(accountId).map { it.toDomain() }

    @Transactional
    override fun deleteByAccountId(accountId: UUID) =
        jpa.deleteByAccountId(accountId)

    override fun delete(id: UUID) = jpa.deleteById(id)
}
