package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.ExclusionPresetRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionPreset
import com.allfolio.unifiedasset.infrastructure.entity.ExclusionPresetEntity
import com.allfolio.unifiedasset.infrastructure.jpa.ExclusionPresetJpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ExclusionPresetRepositoryImpl(
    private val jpa: ExclusionPresetJpaRepository,
) : ExclusionPresetRepository {
    override fun findAll(): List<ExclusionPreset> = jpa.findAll().map { it.toDomain() }

    override fun findBySymbol(symbol: String): ExclusionPreset? = jpa.findBySymbol(symbol)?.toDomain()

    override fun save(preset: ExclusionPreset): ExclusionPreset =
        jpa.save(ExclusionPresetEntity.from(preset)).toDomain()

    override fun delete(id: UUID) = jpa.deleteById(id)
}
