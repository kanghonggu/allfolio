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
    // symbol 정렬로 결정적 순서 보장(프리셋 뷰·스크리닝 lookup 시드 순서 안정).
    override fun findAll(): List<ExclusionPreset> = jpa.findAll().map { it.toDomain() }.sortedBy { it.symbol }

    override fun findBySymbol(symbol: String): ExclusionPreset? = jpa.findBySymbol(symbol)?.toDomain()

    override fun save(preset: ExclusionPreset): ExclusionPreset =
        jpa.save(ExclusionPresetEntity.from(preset)).toDomain()

    override fun delete(id: UUID) = jpa.deleteById(id)
}
