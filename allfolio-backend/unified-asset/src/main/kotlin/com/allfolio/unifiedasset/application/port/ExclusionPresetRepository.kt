package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.exclusion.ExclusionPreset
import java.util.UUID

interface ExclusionPresetRepository {
    fun findAll(): List<ExclusionPreset>
    fun findBySymbol(symbol: String): ExclusionPreset?
    fun save(preset: ExclusionPreset): ExclusionPreset
    fun delete(id: UUID)
}
